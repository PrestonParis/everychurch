"""Backfills blank fields on existing churches from Wikidata — official website,
denomination, Facebook/Instagram/YouTube, phone, email, and a photo. Free, no API key.

Wikidata only has *notable* churches (~12k US church buildings with coordinates: big
parishes, cathedrals, historic/NRHP-listed buildings), so this is a small top-up after
osm_fill.py, not a primary source. Same server endpoint (POST
/api/scraper/bulk-import/fill → ChurchService.fillBlanks: blanks only, never a claimed
church) and the same matching as osm_fill (`match_known`: name similarity scaled by
distance, no proximity-only matches). The 8km "HIFLD put it at the mailing address"
fallback is off here — it needs the item's state, and Wikidata's state comes from a
P131 location chain that's too slow to query nationwide.

**Querying:** the Wikidata Query Service kills anything over 60s. "Every US church" is
fast only in one join order — the ~400 subclasses of church building (Q16970) first,
then their instances, then the country filter — so step 1 forces that order
(`hint:optimizer "None"`) and fetches just ids + coordinates (~10s). Step 2 fetches the
details in batches of item ids.

**Licensing:** Wikidata's data is CC0 — no attribution. Photos are NOT: they live on
Wikimedia Commons, and most need credit (CC BY-SA). The client has nowhere to show a
photo credit, so only images whose Commons metadata says AttributionRequired=false
(CC0 / public domain — about 30% in a 150-image sample) are used. The rest are skipped.

Run manually, after osm_fill (whatever OSM already filled is left alone):

    python -m scraper.wikidata_fill --dry-run
    python -m scraper.wikidata_fill
"""

import argparse
import logging
import re
import time
from typing import Callable, Dict, Iterable, List, Optional, Set, Tuple
from urllib.parse import quote, unquote

import requests

from .bulk_import import DEFAULT_BATCH_DELAY_S, DEFAULT_BATCH_SIZE
from .config import BulkImportConfig
from .existing_churches import fetch_all_existing_churches
from .osm_fill import known_index, log_samples, match_known, merge_fills, submit_fills
from .osm_import import _denomination

logger = logging.getLogger(__name__)

SPARQL_URL = "https://query.wikidata.org/sparql"
COMMONS_API_URL = "https://commons.wikimedia.org/w/api.php"
# Wikimedia's User-Agent policy asks for a way to reach the operator; the site URL,
# deliberately no personal contact details.
USER_AGENT = "everychurch-wikidata-fill/1.0 (https://everychurch.co)"
REQUEST_TIMEOUT_S = 90
MAX_RETRIES = 5
RETRY_BACKOFF_S = 20.0
REQUEST_DELAY_S = 1.0
DETAILS_BATCH_SIZE = 500
# Commons' API caps titles per query at 50.
COMMONS_BATCH_SIZE = 50
# The client shows the photo as an 80px avatar — a 320px thumbnail covers retina
# screens without pulling multi-megabyte originals.
IMAGE_WIDTH_PX = 320

SOURCE_NAME = "wikidata"
FILL_KEYS = ("website", "phone", "email", "facebook", "instagram", "youtube", "denomination", "imageUrl")

_ENTITY_PREFIX = "http://www.wikidata.org/entity/"
_FILE_PATH_PREFIX = "/Special:FilePath/"
_POINT = re.compile(r"^Point\(\s*(-?[\d.]+)\s+(-?[\d.]+)\s*\)$")

IDS_QUERY = """
SELECT DISTINCT ?item ?coord WHERE {
  hint:Query hint:optimizer "None".
  ?type wdt:P279* wd:Q16970.
  ?item wdt:P31 ?type.
  ?item wdt:P17 wd:Q30.
  ?item wdt:P625 ?coord.
}
"""

DETAILS_QUERY = """
SELECT ?item ?label ?website ?image ?religionLabel ?facebook ?instagram ?youtube ?phone ?email WHERE {{
  VALUES ?item {{ {items} }}
  OPTIONAL {{ ?item rdfs:label ?label FILTER(LANG(?label) = "en") }}
  OPTIONAL {{ ?item wdt:P856 ?website }}
  OPTIONAL {{ ?item wdt:P18 ?image }}
  OPTIONAL {{ ?item wdt:P140 ?religion. ?religion rdfs:label ?religionLabel FILTER(LANG(?religionLabel) = "en") }}
  OPTIONAL {{ ?item wdt:P2013 ?facebook }}
  OPTIONAL {{ ?item wdt:P2003 ?instagram }}
  OPTIONAL {{ ?item wdt:P2397 ?youtube }}
  OPTIONAL {{ ?item wdt:P1329 ?phone }}
  OPTIONAL {{ ?item wdt:P968 ?email }}
}}
"""

_DETAIL_VARS = ("label", "website", "image", "religionLabel", "facebook", "instagram", "youtube", "phone", "email")


# --- http ---------------------------------------------------------------------------

def _request(
    session: requests.Session,
    method: str,
    url: str,
    sleep: Callable[[float], None] = time.sleep,
    **kwargs,
) -> dict:
    """JSON from a Wikimedia endpoint, retried on network errors, 429 and 5xx (the query
    service answers a 60s timeout with a 500/502/504, and a truncated 200 body fails to
    parse — both worth one more try after a pause)."""
    last_error: Optional[Exception] = None
    for attempt in range(MAX_RETRIES):
        try:
            response = session.request(
                method, url, headers={"User-Agent": USER_AGENT}, timeout=REQUEST_TIMEOUT_S, **kwargs
            )
            response.raise_for_status()
            return response.json()
        except (requests.exceptions.RequestException, ValueError) as error:
            last_error = error
            logger.warning("%s failed (attempt %d/%d): %s", url, attempt + 1, MAX_RETRIES, error)
            sleep(RETRY_BACKOFF_S * (attempt + 1))
    raise RuntimeError(f"{url} failed after {MAX_RETRIES} attempts") from last_error


def _sparql(session: requests.Session, query: str, sleep: Callable[[float], None] = time.sleep) -> List[dict]:
    # POST, not GET: a 500-id VALUES block is far past a safe URL length.
    body = _request(
        session, "POST", SPARQL_URL, sleep,
        data={"query": query}, params={"format": "json"},
    )
    return body["results"]["bindings"]


# --- fetch --------------------------------------------------------------------------

def _qid(uri: str) -> str:
    return uri[len(_ENTITY_PREFIX):] if uri.startswith(_ENTITY_PREFIX) else uri


def _point(wkt: str) -> Optional[Tuple[float, float]]:
    """WKT "Point(lon lat)" → (lat, lon)."""
    match = _POINT.match(wkt or "")
    if not match:
        return None
    return float(match.group(2)), float(match.group(1))


def fetch_church_ids(session: requests.Session, sleep: Callable[[float], None] = time.sleep) -> Dict[str, dict]:
    """{qid: {"lat", "lon"}} for every US church building with coordinates. An item with
    two coordinates keeps the first."""
    churches: Dict[str, dict] = {}
    for row in _sparql(session, IDS_QUERY, sleep):
        point = _point(row["coord"]["value"])
        if point:
            churches.setdefault(_qid(row["item"]["value"]), {"lat": point[0], "lon": point[1]})
    return churches


def fetch_details(
    session: requests.Session,
    qids: List[str],
    sleep: Callable[[float], None] = time.sleep,
) -> Dict[str, Dict[str, Set[str]]]:
    """{qid: {var: {values}}} — sets, because any property can have several values and
    each combination is its own result row."""
    details: Dict[str, Dict[str, Set[str]]] = {}
    for start in range(0, len(qids), DETAILS_BATCH_SIZE):
        if start:
            sleep(REQUEST_DELAY_S)
        batch = qids[start:start + DETAILS_BATCH_SIZE]
        query = DETAILS_QUERY.format(items=" ".join(f"wd:{qid}" for qid in batch))
        for row in _sparql(session, query, sleep):
            item = details.setdefault(_qid(row["item"]["value"]), {})
            for var in _DETAIL_VARS:
                if var in row:
                    item.setdefault(var, set()).add(row[var]["value"])
        logger.info("wikidata details: %d/%d items", start + len(batch), len(qids))
    return details


def _commons_file(image_uri: str) -> Optional[str]:
    """P18 values come back as .../Special:FilePath/<url-encoded file name>."""
    if _FILE_PATH_PREFIX not in image_uri:
        return None
    return unquote(image_uri.split(_FILE_PATH_PREFIX, 1)[1])


def fetch_free_images(
    session: requests.Session,
    files: Iterable[str],
    sleep: Callable[[float], None] = time.sleep,
) -> Set[str]:
    """The subset of Commons file names that need no attribution."""
    files = sorted(set(files))
    free: Set[str] = set()
    for start in range(0, len(files), COMMONS_BATCH_SIZE):
        if start:
            sleep(REQUEST_DELAY_S)
        batch = files[start:start + COMMONS_BATCH_SIZE]
        body = _request(session, "GET", COMMONS_API_URL, sleep, params={
            "action": "query",
            "prop": "imageinfo",
            "iiprop": "extmetadata",
            "iiextmetadatafilter": "AttributionRequired",
            "titles": "|".join(f"File:{name}" for name in batch),
            "format": "json",
            "formatversion": "2",
        })
        for page in body.get("query", {}).get("pages", []):
            info = (page.get("imageinfo") or [{}])[0]
            # A file with no metadata comes back as "extmetadata": [] (PHP's empty array),
            # not {}: treat it as unknown, which keeps the image out.
            meta = info.get("extmetadata")
            field = meta.get("AttributionRequired") if isinstance(meta, dict) else None
            required = field.get("value") if isinstance(field, dict) else None
            if required == "false":
                free.add(page["title"][len("File:"):])
    return free


# --- map ----------------------------------------------------------------------------

def _one(values: Optional[Set[str]]) -> Optional[str]:
    """The value only when there's exactly one — two official websites or two phone
    numbers on one item is a coin flip, and a blank is better than the wrong one."""
    if not values or len(values) != 1:
        return None
    value = next(iter(values)).strip()
    return value or None


def _url(raw: Optional[str]) -> Optional[str]:
    return raw if raw and re.match(r"^https?://\S+$", raw, re.IGNORECASE) else None


def _handle(raw: Optional[str], base: str) -> Optional[str]:
    return f"{base}{quote(raw)}" if raw and " " not in raw else None


def _email(raw: Optional[str]) -> Optional[str]:
    """P968 is a mailto: URI."""
    if not raw:
        return None
    address = raw[len("mailto:"):] if raw.lower().startswith("mailto:") else raw
    return address if re.match(r"^[^@\s]+@[^@\s]+\.[^@\s]+$", address) else None


def _denomination_of(labels: Optional[Set[str]]) -> Optional[str]:
    """Several P140 values are common ("Christianity" + "Catholicism") — fine as long as
    they map to one label between them."""
    mapped = {_denomination(label) for label in labels or ()} - {None}
    return mapped.pop() if len(mapped) == 1 else None


def _image_url(image_uris: Optional[Set[str]], free_images: Set[str]) -> Optional[str]:
    uri = _one(image_uris)
    name = _commons_file(uri) if uri else None
    if not name or name not in free_images:
        return None
    return f"https://commons.wikimedia.org/wiki/Special:FilePath/{quote(name)}?width={IMAGE_WIDTH_PX}"


def wikidata_fields(item: Dict[str, Set[str]], free_images: Set[str]) -> dict:
    fields = {
        "website": _url(_one(item.get("website"))),
        "phone": _one(item.get("phone")),
        "email": _email(_one(item.get("email"))),
        "facebook": _handle(_one(item.get("facebook")), "https://www.facebook.com/"),
        "instagram": _handle(_one(item.get("instagram")), "https://www.instagram.com/"),
        "youtube": _handle(_one(item.get("youtube")), "https://www.youtube.com/channel/"),
        "denomination": _denomination_of(item.get("religionLabel")),
        "imageUrl": _image_url(item.get("image"), free_images),
    }
    return {key: value for key, value in fields.items() if value}


def build_fills(
    churches: Dict[str, dict],
    details: Dict[str, Dict[str, Set[str]]],
    free_images: Set[str],
    existing: Iterable[dict],
) -> Tuple[List[dict], dict]:
    index = known_index(existing)
    stats = {"wikidataChurches": len(churches), "withFillableFields": 0, "matched": 0, "unmatched": 0}
    matches = []
    for qid, location in churches.items():
        item = details.get(qid, {})
        fields = wikidata_fields(item, free_images)
        name = _one(item.get("label"))
        if not fields or not name:
            continue
        stats["withFillableFields"] += 1
        # match_known speaks OSM elements; the state is None so its state-gated 8km
        # fallback never fires (see the module docstring).
        element = {"lat": location["lat"], "lon": location["lon"], "tags": {"name": name}}
        known = match_known(element, None, index)
        if known is None:
            stats["unmatched"] += 1
            continue
        stats["matched"] += 1
        matches.append((known, fields))
    fills, merge_stats = merge_fills(matches, FILL_KEYS)
    return fills, {**stats, **merge_stats}


# --- run ----------------------------------------------------------------------------

def run(
    config: Optional[BulkImportConfig] = None,
    session: Optional[requests.Session] = None,
    dry_run: bool = False,
    batch_size: int = DEFAULT_BATCH_SIZE,
    batch_delay_s: float = DEFAULT_BATCH_DELAY_S,
    sleep: Callable[[float], None] = time.sleep,
) -> dict:
    config = config or BulkImportConfig.from_env()
    session = session or requests.Session()

    churches = fetch_church_ids(session, sleep)
    logger.info("wikidata: %d US church buildings with coordinates", len(churches))
    details = fetch_details(session, sorted(churches), sleep)
    image_files = [
        name for item in details.values()
        for name in [_commons_file(_one(item.get("image")) or "")] if name
    ]
    free_images = fetch_free_images(session, image_files, sleep)
    logger.info("commons: %d of %d images need no attribution", len(free_images), len(set(image_files)))

    existing = fetch_all_existing_churches(config.server_base_url, session)
    fills, stats = build_fills(churches, details, free_images, existing)
    logger.info("wikidata fill plan: %s", stats)

    if dry_run:
        log_samples(fills, {c.get("id"): c for c in existing})
        return {**stats, "updated": 0}

    updated = 0
    for start in range(0, len(fills), batch_size):
        batch = fills[start:start + batch_size]
        updated += submit_fills(config, session, batch, source=SOURCE_NAME)
        logger.info("wikidata fill progress: %d/%d sent, %d updated", start + len(batch), len(fills), updated)
        if start + batch_size < len(fills):
            sleep(batch_delay_s)

    result = {**stats, "updated": updated}
    logger.info("wikidata fill run: %s", result)
    return result


def main(argv: Optional[List[str]] = None) -> dict:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--dry-run", action="store_true", help="match and report, send nothing")
    args = parser.parse_args(argv)
    return run(dry_run=args.dry_run)


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    main()
