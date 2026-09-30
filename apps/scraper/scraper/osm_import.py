"""Bulk seed from OpenStreetMap (`amenity=place_of_worship` + `religion=christian`),
queried state-by-state from the public Overpass API. Free, no API key.

The second free source after hifld_import.py (see docs/SCRAPER.md "OpenStreetMap
import"), and it fills a different gap: HIFLD is built from IRS filings, so it misses
every congregation that never applied for recognition of exemption — OSM is mapped by
people standing in front of the building, not gated on IRS paperwork.

**Run hifld_import.py first.** Dedup here is against whatever the server already has,
and most OSM churches are also in HIFLD — running this first would import them from the
sparser source (63% of OSM rows have no city/ZIP) and then HIFLD's name+ZIP dedup
wouldn't recognize them as the same church.

**Why dedup is spatial, not name+ZIP like the other imports:** a 2026-09-30 Rhode
Island probe found only 37% of OSM rows carry `addr:postcode`. Keyed on name+ZIP, every
ZIP-less "First Baptist Church" in the country would collapse into one row (within the
batch) or be dropped as a match for some unrelated existing one. Every OSM row does have
coordinates, and so does every church already on the server that came from HIFLD or
Kaggle, so matching is name similarity scaled by distance — see `_is_same_church`.

**Licensing:** OSM data is ODbL — the client must show "© OpenStreetMap contributors"
attribution wherever these churches are displayed, and the church table built from it
is covered by ODbL's share-alike terms.

Deliberately NOT wired into pipeline.run() or the recurring Lambda — run manually:

    python -m scraper.osm_import
"""

import logging
import math
import re
import time
from difflib import SequenceMatcher
from typing import Callable, Dict, Iterable, List, Optional, Tuple

import requests

from .bulk_import import DEFAULT_BATCH_DELAY_S, DEFAULT_BATCH_SIZE, submit_batch
from .config import BulkImportConfig
from .existing_churches import fetch_all_existing_churches
from .regions import STATE_BOUNDING_BOXES

logger = logging.getLogger(__name__)

OVERPASS_URL = "https://overpass-api.de/api/interpreter"
# Overpass's usage policy asks clients to identify themselves; deliberately no personal
# contact details here.
OVERPASS_USER_AGENT = "everychurch-osm-import/1.0"
OVERPASS_TIMEOUT_S = 300
OVERPASS_MAX_RETRIES = 5
OVERPASS_RETRY_BACKOFF_S = 30.0
# One query per state; the public instance is shared, so pause between them rather than
# firing 51 heavy queries back to back.
OVERPASS_STATE_DELAY_S = 10.0

# regions.py covers the 50 states only; DC is its own ISO 3166-2 area in OSM.
STATE_CODES = tuple(sorted(set(STATE_BOUNDING_BOXES) | {"DC"}))

SOURCE_NAME = "osm"

# `out center tags` gives nodes their own lat/lon and ways/relations (church buildings
# are mostly drawn as outlines) a computed `center` — one coordinate per result either
# way, and no geometry payload beyond that.
_QUERY_TEMPLATE = (
    '[out:json][timeout:{timeout}];'
    'area["ISO3166-2"="US-{state}"]->.a;'
    'nwr["amenity"="place_of_worship"]["religion"="christian"](area.a);'
    'out center tags;'
)

# Non-congregation places that are still tagged amenity=place_of_worship in OSM — all
# seen in the Rhode Island probe or the HIFLD exclusion list: religious houses,
# institutional chapels, and camps. Word-boundary matched so "home" doesn't hit
# "Homestead Baptist Church". Unlike HIFLD's list, "college"/"university"/"camp" are
# NOT here: in OSM they're overwhelmingly place names ("College Park Baptist Church",
# "Camp Hill Presbyterian"), not an institution filing under its own name. "home"
# only counts at the end of the name ("Saint Marys Home"), since "Home Mission Baptist
# Church" is a real congregation.
_EXCLUDE_NAME_PATTERN = re.compile(
    r"\b("
    r"monastery|convent|friary|hermitage|priory|sisters of|missionaries of|carmelite"
    r"|school|academy|seminary|hospital|medical center|cemetery"
    r"|nursing|retirement|campground|retreat center|prison|correctional"
    r")\b"
    r"|\bhome$"
)
# A name that says it's a congregation overrides the exclusions above — the nationwide
# dry run showed them otherwise dropping real churches: "Faith Lutheran Church and
# School", "Old School Baptist Church" (a denomination), dozens of rural "Campground
# Church"es. "chapel" is deliberately not here: hospital/cemetery/school chapels are
# exactly what the exclusions exist to catch.
_CONGREGATION_NAME_PATTERN = re.compile(r"\b(church|cathedral|basilica|parish|tabernacle|meeting ?house)\b")
# `place_of_worship=*` subtypes that are a roadside marker, not a congregation.
_EXCLUDE_PLACE_OF_WORSHIP = {"shrine", "wayside_shrine", "cross", "wayside_cross"}

# OSM `denomination` values → DENOMINATIONS (church.model.ts). Ordered: the first match
# wins, and several real values contain another's keyword — "african_methodist_episcopal"
# is Methodist, not Episcopal; "evangelical_lutheran" is Lutheran, not Evangelical;
# "united_church_of_christ" is Congregational, not Church of Christ.
_DENOMINATION_KEYWORDS = (
    ("united_church_of_christ", None),
    ("latter_day", "Latter-day Saint (LDS)"),
    ("mormon", "Latter-day Saint (LDS)"),
    ("adventist", "Seventh-day Adventist"),
    ("methodist", "Methodist"),
    ("lutheran", "Lutheran"),
    ("baptist", "Baptist"),
    ("catholic", "Catholic"),
    ("orthodox", "Eastern Orthodox"),
    ("anglican", "Episcopal/Anglican"),
    ("episcopal", "Episcopal/Anglican"),
    ("presbyterian", "Presbyterian"),
    ("pentecostal", "Pentecostal / Assembly of God"),
    ("assemblies_of_god", "Pentecostal / Assembly of God"),
    ("nondenominational", "Non-denominational"),
    ("non_denominational", "Non-denominational"),
    ("church_of_christ", "Church of Christ"),
    ("churches_of_christ", "Church of Christ"),
    ("evangelical", "Evangelical"),
)

# Matching thresholds (_is_same_church). HIFLD coordinates are geocoded from an IRS
# filing address, which is sometimes the pastor's house or a PO box rather than the
# building, so a strong name match is allowed to sit well away from the OSM point;
# a weak one has to be nearly on top of it.
_SAME_PLACE_M = 30.0
_WEAK_NAME_RADIUS_M = 150.0
_WEAK_NAME_RATIO = 0.6
_STRONG_NAME_RADIUS_M = 1000.0
_STRONG_NAME_RATIO = 0.85
# City fill (_fill_city): only borrow a nearby known church's city when it's this close.
_CITY_FILL_RADIUS_M = 1500.0
# Known-church-only matching (_is_misplaced_known_church): an exact name, or the name
# plus the known church's own town ("Calvary Chapel" vs "CALVARY CHAPEL RAMONA"), in the
# same state, this far out. The nationwide dry run found ~4,600 such pairs 1-8km apart —
# HIFLD placing the church at its IRS mailing address — that the 1km rule let through.
_MISPLACED_RADIUS_M = 8000.0

_GRID_DEG = 0.02
_EARTH_RADIUS_M = 6_371_000.0

_NON_ALNUM = re.compile(r"[^a-z0-9 ]")
_NAME_REWRITES = ((r"\bst\b", "saint"), (r"\bste\b", "sainte"), (r"\bmt\b", "mount"), (r"&", " and "))
_NAME_NOISE = re.compile(r"\b(the|inc|incorporated|corp|corporation|of|llc)\b")


# --- fetch --------------------------------------------------------------------------

def _fetch_state(
    session: requests.Session,
    state: str,
    sleep: Callable[[float], None] = time.sleep,
) -> List[dict]:
    """One state's elements, retried on network errors and on Overpass's own overload
    responses (429 rate limit, 504 gateway timeout — both routine on the shared public
    instance, not a real failure)."""
    query = _QUERY_TEMPLATE.format(timeout=OVERPASS_TIMEOUT_S, state=state)
    last_error: Optional[Exception] = None

    for attempt in range(OVERPASS_MAX_RETRIES):
        try:
            response = session.post(
                OVERPASS_URL,
                data={"data": query},
                headers={"User-Agent": OVERPASS_USER_AGENT},
                timeout=OVERPASS_TIMEOUT_S + 30,
            )
            response.raise_for_status()
            body = response.json()
            # Overpass reports a query-side timeout/memory abort as HTTP 200 with a
            # `remark` and a truncated element list — treat it as a failure so a
            # partial state never silently passes for a complete one.
            remark = body.get("remark") or ""
            if "runtime error" in remark:
                raise RuntimeError(f"Overpass aborted the {state} query: {remark}")
            return body.get("elements", [])
        except (requests.exceptions.RequestException, RuntimeError) as error:
            last_error = error
            logger.warning("overpass %s failed (attempt %d/%d): %s", state, attempt + 1, OVERPASS_MAX_RETRIES, error)
            sleep(OVERPASS_RETRY_BACKOFF_S * (attempt + 1))

    raise RuntimeError(f"Overpass query for {state} failed after {OVERPASS_MAX_RETRIES} attempts") from last_error


def fetch_all_elements(
    session: Optional[requests.Session] = None,
    states: Iterable[str] = STATE_CODES,
    sleep: Callable[[float], None] = time.sleep,
) -> List[Tuple[str, dict]]:
    """(state, element) pairs — the queried state is kept alongside each element
    because `addr:state` is missing on most rows, and the area it was found in is the
    authoritative answer anyway."""
    session = session or requests.Session()
    results: List[Tuple[str, dict]] = []
    for index, state in enumerate(states):
        if index:
            sleep(OVERPASS_STATE_DELAY_S)
        elements = _fetch_state(session, state, sleep)
        logger.info("overpass %s: %d elements", state, len(elements))
        results.extend((state, element) for element in elements)
    return results


# --- classify / map -----------------------------------------------------------------

def _clean(value) -> Optional[str]:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _coordinates(element: dict) -> Tuple[Optional[float], Optional[float]]:
    if "lat" in element and "lon" in element:
        return element["lat"], element["lon"]
    center = element.get("center") or {}
    return center.get("lat"), center.get("lon")


def is_church(element: dict) -> bool:
    tags = element.get("tags") or {}
    name = _clean(tags.get("name"))
    if name is None:
        return False
    if tags.get("place_of_worship") in _EXCLUDE_PLACE_OF_WORSHIP:
        return False
    lower = name.lower().rstrip(" .")
    if _EXCLUDE_NAME_PATTERN.search(lower) and not _CONGREGATION_NAME_PATTERN.search(lower):
        return False
    latitude, longitude = _coordinates(element)
    return latitude is not None and longitude is not None


def _denomination(raw: Optional[str]) -> Optional[str]:
    """None when there's no tag or nothing matches — same convention as the other
    imports: an honest blank beats a guessed label."""
    # Mappers mix separators ("latter-day_saints", "non-denominational") — fold them
    # all to underscores so one keyword covers every spelling.
    value = re.sub(r"[-\s]+", "_", (_clean(raw) or "").lower())
    if not value:
        return None
    for keyword, denomination in _DENOMINATION_KEYWORDS:
        if keyword in value:
            return denomination
    return None


def _zip_code(raw) -> Optional[str]:
    digits = (_clean(raw) or "").split("-")[0].strip()
    return digits if len(digits) == 5 and digits.isdigit() else None


def _address(tags: dict) -> Optional[str]:
    street = _clean(tags.get("addr:street"))
    if street is None:
        return None
    number = _clean(tags.get("addr:housenumber"))
    return f"{number} {street}" if number else street


def to_church_dto_shape(state: str, element: dict) -> dict:
    """Same wire format as the other imports (ChurchDto.java, camelCase)."""
    tags = element.get("tags") or {}
    latitude, longitude = _coordinates(element)
    return {
        "name": _clean(tags.get("name")),
        "denomination": _denomination(tags.get("denomination")),
        "address": _address(tags),
        "city": _clean(tags.get("addr:city")),
        "state": state,
        "zipCode": _zip_code(tags.get("addr:postcode")),
        "phone": _clean(tags.get("phone")) or _clean(tags.get("contact:phone")),
        "website": _clean(tags.get("website")) or _clean(tags.get("contact:website")),
        "latitude": latitude,
        "longitude": longitude,
    }


# --- spatial dedup ------------------------------------------------------------------

def _normalize_name(name: Optional[str]) -> str:
    text = (name or "").lower()
    for pattern, replacement in _NAME_REWRITES:
        text = re.sub(pattern, replacement, text)
    text = _NON_ALNUM.sub(" ", text)
    text = _NAME_NOISE.sub(" ", text)
    return " ".join(text.split())


def _distance_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    d_phi = phi2 - phi1
    d_lambda = math.radians(lon2 - lon1)
    a = math.sin(d_phi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(d_lambda / 2) ** 2
    return 2 * _EARTH_RADIUS_M * math.asin(math.sqrt(a))


def _is_same_church(name_a: str, name_b: str, distance_m: float) -> bool:
    """name_a/name_b are already _normalize_name'd."""
    if distance_m <= _SAME_PLACE_M:
        return True
    if distance_m > _STRONG_NAME_RADIUS_M:
        return False
    if not name_a or not name_b:
        return False
    ratio = SequenceMatcher(None, name_a, name_b).ratio()
    # One name containing the other ("grace" vs "grace community") counts as strong —
    # OSM names are often the short form of the filed one.
    if name_a in name_b or name_b in name_a:
        ratio = max(ratio, _STRONG_NAME_RATIO)
    if ratio >= _STRONG_NAME_RATIO:
        return True
    return distance_m <= _WEAK_NAME_RADIUS_M and ratio >= _WEAK_NAME_RATIO


class SpatialIndex:
    """Grid-bucketed lookup of known churches, so matching ~150k OSM rows against
    ~200k known ones is a handful of comparisons each instead of 30 billion."""

    def __init__(self) -> None:
        self._cells: Dict[Tuple[int, int], List[Tuple[float, float, str, dict]]] = {}

    @staticmethod
    def _cell(latitude: float, longitude: float) -> Tuple[int, int]:
        return int(math.floor(latitude / _GRID_DEG)), int(math.floor(longitude / _GRID_DEG))

    def add(self, church: dict) -> None:
        latitude, longitude = church.get("latitude"), church.get("longitude")
        if latitude is None or longitude is None:
            return
        entry = (latitude, longitude, _normalize_name(church.get("name")), church)
        self._cells.setdefault(self._cell(latitude, longitude), []).append(entry)

    def nearby(self, latitude: float, longitude: float, radius_m: float) -> List[Tuple[float, str, dict]]:
        """(distance, normalized name, church) for everything within radius_m."""
        lat_span = math.ceil(radius_m / 111_000 / _GRID_DEG)
        # Longitude degrees shrink with latitude; clamp so Alaska doesn't divide by ~0.
        lon_scale = max(math.cos(math.radians(latitude)), 0.2)
        lon_span = math.ceil(radius_m / (111_000 * lon_scale) / _GRID_DEG)
        row, col = self._cell(latitude, longitude)
        found = []
        for d_row in range(-lat_span, lat_span + 1):
            for d_col in range(-lon_span, lon_span + 1):
                for lat, lon, name, church in self._cells.get((row + d_row, col + d_col), ()):
                    distance = _distance_m(latitude, longitude, lat, lon)
                    if distance <= radius_m:
                        found.append((distance, name, church))
        return found

    def has_match(self, church: dict) -> bool:
        name = _normalize_name(church.get("name"))
        return any(
            _is_same_church(name, other_name, distance)
            for distance, other_name, _ in self.nearby(church["latitude"], church["longitude"], _STRONG_NAME_RADIUS_M)
        )


def _is_misplaced_known_church(church: dict, index: SpatialIndex) -> bool:
    """Only ever applied against already-known churches, never between OSM rows: OSM
    positions are the building itself, so two same-named OSM churches 5km apart (two
    rural "Church of God"s) really are two churches. It's the known side's coordinates
    that can't be trusted this far out."""
    name = _normalize_name(church.get("name"))
    if not name:
        return False
    for _, other_name, other in index.nearby(church["latitude"], church["longitude"], _MISPLACED_RADIUS_M):
        if other.get("state") != church.get("state") or not other_name:
            continue
        if other_name == name:
            return True
        city = _normalize_name(other.get("city"))
        if city and other_name == f"{name} {city}":
            return True
    return False


def dedupe(candidates: List[dict], known: Iterable[dict]) -> List[dict]:
    """Drops candidates matching a known church, and later candidates matching an
    earlier one (OSM sometimes maps the same church as both a node and a building
    outline). Accepted candidates join the batch index as they're kept, which is what
    makes the within-batch half work."""
    known_index = SpatialIndex()
    for church in known:
        known_index.add(church)
    batch_index = SpatialIndex()
    kept = []
    for church in candidates:
        if known_index.has_match(church) or _is_misplaced_known_church(church, known_index):
            continue
        if batch_index.has_match(church):
            continue
        kept.append(church)
        batch_index.add(church)
    return kept


def _fill_city(church: dict, index: SpatialIndex) -> dict:
    """Most OSM rows have no addr:city, and the directory's city search is a text
    match on that column — borrow the city of the nearest known church in the same
    state, if one is close enough to be confident. ZIP is deliberately NOT borrowed:
    ZIP boundaries are much finer than city limits, so a neighbor's ZIP is wrong far
    more often, and a blank is better than a wrong one."""
    if church.get("city"):
        return church
    neighbors = [
        (distance, other)
        for distance, _, other in index.nearby(church["latitude"], church["longitude"], _CITY_FILL_RADIUS_M)
        if other.get("city") and other.get("state") == church.get("state")
    ]
    if neighbors:
        _, nearest = min(neighbors, key=lambda pair: pair[0])
        church = {**church, "city": nearest["city"]}
    return church


# --- run ----------------------------------------------------------------------------

def run(
    config: Optional[BulkImportConfig] = None,
    session: Optional[requests.Session] = None,
    states: Iterable[str] = STATE_CODES,
    batch_size: int = DEFAULT_BATCH_SIZE,
    batch_delay_s: float = DEFAULT_BATCH_DELAY_S,
) -> dict:
    """fetch (per state) -> filter -> map -> spatial dedup against the server -> fill
    city -> ingest via the same trusted bulk-import endpoint as the other imports.
    Resumable: a re-run dedups against whatever already made it in, since everything
    it created has coordinates."""
    config = config or BulkImportConfig.from_env()
    session = session or requests.Session()

    elements = fetch_all_elements(session, states)
    church_elements = [(state, element) for state, element in elements if is_church(element)]
    mapped = [to_church_dto_shape(state, element) for state, element in church_elements]

    existing = fetch_all_existing_churches(config.server_base_url, session)
    new_churches = dedupe(mapped, existing)

    city_index = SpatialIndex()
    for church in existing:
        city_index.add(church)
    missing_city = sum(1 for c in new_churches if not c.get("city"))
    new_churches = [_fill_city(c, city_index) for c in new_churches]
    still_missing_city = sum(1 for c in new_churches if not c.get("city"))

    created = 0
    for start in range(0, len(new_churches), batch_size):
        batch = new_churches[start:start + batch_size]
        created += submit_batch(config, session, batch, source=SOURCE_NAME, total_new=len(new_churches))
        logger.info("osm import progress: %d/%d ingested", start + len(batch), len(new_churches))
        if start + batch_size < len(new_churches):
            time.sleep(batch_delay_s)

    result = {
        "totalElements": len(elements),
        "churchElements": len(church_elements),
        "new": len(new_churches),
        "cityFilled": missing_city - still_missing_city,
        "cityMissing": still_missing_city,
        "created": created,
    }
    logger.info("osm import run: %s", result)
    return result


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    run()
