"""Backfills blank fields (website, phone, email, social links, denomination, service
times) on churches that already exist, from the OpenStreetMap tags of the same
building. Free, no API key — same per-state Overpass query as osm_import.py.

osm_import.py only ever *adds* churches OSM has and the server doesn't. Most OSM
churches matched an existing HIFLD/Kaggle row there and were dropped, along with
whatever contact details their mappers had tagged — HIFLD has no phone or website
column at all, so those rows are blank. This matches each OSM church to its existing
row and sends only the fields that row is missing.

**Matching is stricter than osm_import's dedup.** A dedup false positive just skips an
OSM church; a fill false positive puts another church's website on this one. So, unlike
`_is_same_church`, two buildings 30m apart are not assumed to be one church — there
must be a name match:
  - within 1km: name ratio >= 0.85, or one name contains the other (and the shorter
    has at least two words, so a bare "Church" or "Grace" never contains-matches)
  - within 150m: ratio >= 0.6
  - only if neither found anything, within 8km in the same state: the exact name, or
    the name plus the known church's own city ("CALVARY CHAPEL RAMONA") — HIFLD
    geocoding the IRS mailing address instead of the building — and only when exactly
    one known church qualifies.
When several OSM elements land on one known church (a node plus a building outline)
their fields are merged, and a field they disagree on is dropped rather than guessed.

**Server side never overwrites.** POST /api/scraper/bulk-import/fill
(ChurchService.fillBlanks) only sets fields that are blank on the row, adds service
times only when the row has none, and skips claimed churches entirely. Re-running is
safe; so is running while an import is still in progress (later rows just get picked
up by the next run).

Run manually, after the imports have finished (it pages the whole directory):

    python -m scraper.osm_fill --dry-run --cache osm_elements.json
    python -m scraper.osm_fill --cache osm_elements.json

`--cache` saves the Overpass download as it goes (after every state) and reads it back
afterwards, so a dry run followed by the real run queries the shared public instance only
once — and if a state fails all its retries, re-running the same command resumes from
the states still missing instead of starting over.
"""

import argparse
import json
import logging
import os
import random
import re
import time
from difflib import SequenceMatcher
from typing import Callable, Dict, Iterable, List, Optional, Tuple

import requests

from .bulk_import import BULK_IMPORT_ENDPOINT, BULK_IMPORT_HEADER, DEFAULT_BATCH_DELAY_S, DEFAULT_BATCH_SIZE
from .config import BulkImportConfig
from .existing_churches import fetch_all_existing_churches
from .osm_import import (
    OVERPASS_STATE_DELAY_S,
    _MISPLACED_RADIUS_M,
    _STRONG_NAME_RADIUS_M,
    _STRONG_NAME_RATIO,
    _WEAK_NAME_RADIUS_M,
    _WEAK_NAME_RATIO,
    STATE_CODES,
    SpatialIndex,
    _clean,
    _coordinates,
    _denomination,
    _normalize_name,
    fetch_all_elements,
    is_church,
)

logger = logging.getLogger(__name__)

FILL_ENDPOINT = f"{BULK_IMPORT_ENDPOINT}/fill"
SOURCE_NAME = "osm"

# ChurchDto fields this script can fill, in the order they're reported.
TEXT_FIELDS = ("website", "phone", "email", "facebook", "instagram", "youtube", "denomination")

_DAYS = ("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
_DAY_NAMES = {
    "Mo": "Monday", "Tu": "Tuesday", "We": "Wednesday", "Th": "Thursday",
    "Fr": "Friday", "Sa": "Saturday", "Su": "Sunday",
}
_DAY = r"(?:Mo|Tu|We|Th|Fr|Sa|Su)"
_DAY_SPEC = rf"{_DAY}(?:-{_DAY})?(?:,{_DAY}(?:-{_DAY})?)*"
# "+" is opening_hours for open-ended ("Su 09:30+") — still just a start time here.
_TIME_SPEC = r"\d{1,2}:\d{2}(?:-\d{1,2}:\d{2}|\+)?"
# One opening_hours-syntax rule as mappers write `service_times`: "Su 08:00,10:30",
# "Mo-Fr 07:00", "Sa,Su 09:00-10:00". Anything richer — month ranges, "PH off",
# week numbers, free text — fails the whole tag rather than half-parsing it.
# Quoted comments ('Su 07:00 "Sunday mass"') are stripped first.
_RULE = re.compile(rf"^(?P<days>{_DAY_SPEC})\s+(?P<times>{_TIME_SPEC}(?:\s*,\s*{_TIME_SPEC})*)$")
_COMMENT = re.compile(r'"[^"]*"')
# A real schedule rarely has more than a dozen services; past this it's more likely a
# mis-tagged opening_hours (e.g. "Mo-Su 08:00-20:00" style) than services.
_MAX_SERVICE_TIMES = 20

_EMAIL = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")


# --- OSM tags → ChurchDto fields ----------------------------------------------------

def _first(value: Optional[str]) -> Optional[str]:
    """OSM uses ";" to separate multiple values in one tag — keep the first."""
    text = _clean(value)
    return _clean(text.split(";")[0]) if text else None


def _tag(tags: dict, *keys: str) -> Optional[str]:
    for key in keys:
        value = _first(tags.get(key))
        if value:
            return value
    return None


def _website(raw: Optional[str]) -> Optional[str]:
    if not raw or " " in raw:
        return None
    if not re.match(r"^https?://", raw, re.IGNORECASE):
        # http, not https: plenty of small-church sites are still http-only, and an
        # http link to an https site redirects while the reverse just fails.
        raw = f"http://{raw}"
    return raw


def _social(raw: Optional[str], base: str) -> Optional[str]:
    """`contact:facebook` etc. are a full URL or a bare handle, depending on the mapper."""
    if not raw or " " in raw:
        return None
    if re.match(r"^https?://", raw, re.IGNORECASE):
        return raw
    return f"{base}{raw.lstrip('@')}"


def _youtube(raw: Optional[str]) -> Optional[str]:
    # A bare YouTube handle is ambiguous (channel id, legacy user, @handle) — only a URL.
    return raw if raw and re.match(r"^https?://", raw, re.IGNORECASE) else None


def _email(raw: Optional[str]) -> Optional[str]:
    return raw if raw and _EMAIL.match(raw) else None


def _expand_days(spec: str) -> List[str]:
    days: List[str] = []
    for part in spec.split(","):
        if "-" in part:
            start, end = (_DAYS.index(d) for d in part.split("-"))
            # Wraps past Sunday: "Sa-Mo" is Sa, Su, Mo.
            span = (end - start) % 7
            days.extend(_DAYS[(start + i) % 7] for i in range(span + 1))
        else:
            days.append(part)
    return days


def _start_time(spec: str) -> Optional[str]:
    """"10:30" or "10:30-11:45" → "10:30" (ServiceTime.time is one start time, "HH:MM")."""
    hour, minute = (int(x) for x in spec.split("-")[0].rstrip("+").split(":"))
    if hour > 23 or minute > 59:
        return None
    return f"{hour:02d}:{minute:02d}"


def parse_service_times(raw: Optional[str], service_type: str) -> Optional[List[dict]]:
    """The `service_times` tag as ServiceTimeDto dicts, or None if any part of it isn't
    the simple day+time form — a partial schedule would read as the complete one."""
    text = _clean(raw)
    if not text:
        return None
    seen = set()
    times: List[dict] = []
    for rule in _COMMENT.sub("", text).split(";"):
        rule = rule.strip()
        if not rule:
            continue
        match = _RULE.match(rule)
        if not match:
            return None
        for day in _expand_days(match.group("days")):
            for time_spec in match.group("times").split(","):
                start = _start_time(time_spec.strip())
                if start is None:
                    return None
                key = (day, start)
                if key not in seen:
                    seen.add(key)
                    times.append({"dayOfWeek": _DAY_NAMES[day], "time": start, "type": service_type})
    if not times or len(times) > _MAX_SERVICE_TIMES:
        return None
    return times


def _service_type(denomination: Optional[str]) -> str:
    # The client's ServiceTime types are "Worship" / "Mass" / "Bible Study".
    return "Mass" if denomination and "cath" in denomination.lower() else "Worship"


def osm_fields(element: dict, known_denomination: Optional[str] = None) -> dict:
    """Every fillable field this element has, non-blank only. known_denomination (the
    matched church's own) decides Mass vs Worship before OSM's tag does."""
    tags = element.get("tags") or {}
    denomination = _denomination(tags.get("denomination"))
    fields = {
        "website": _website(_tag(tags, "website", "contact:website", "url")),
        "phone": _tag(tags, "phone", "contact:phone"),
        "email": _email(_tag(tags, "email", "contact:email")),
        "facebook": _social(_tag(tags, "contact:facebook", "facebook"), "https://www.facebook.com/"),
        "instagram": _social(_tag(tags, "contact:instagram", "instagram"), "https://www.instagram.com/"),
        "youtube": _youtube(_tag(tags, "contact:youtube", "youtube")),
        "denomination": denomination,
        "serviceTimes": parse_service_times(
            tags.get("service_times"), _service_type(known_denomination or denomination)
        ),
    }
    return {key: value for key, value in fields.items() if value}


# --- matching -----------------------------------------------------------------------

def _name_score(osm_name: str, known_name: str, distance_m: float) -> Optional[float]:
    """Both names already _normalize_name'd. None means not a match."""
    if not osm_name or not known_name:
        return None
    ratio = SequenceMatcher(None, osm_name, known_name).ratio()
    shorter, longer = sorted((osm_name, known_name), key=len)
    if shorter in longer and len(shorter.split()) >= 2:
        ratio = max(ratio, _STRONG_NAME_RATIO)
    if ratio >= _STRONG_NAME_RATIO and distance_m <= _STRONG_NAME_RADIUS_M:
        return ratio
    if ratio >= _WEAK_NAME_RATIO and distance_m <= _WEAK_NAME_RADIUS_M:
        return ratio
    return None


def match_known(element: dict, state: str, index: SpatialIndex) -> Optional[dict]:
    """The single existing church this OSM element is, or None."""
    tags = element.get("tags") or {}
    name = _normalize_name(tags.get("name"))
    latitude, longitude = _coordinates(element)

    near = []
    for distance, known_name, known in index.nearby(latitude, longitude, _STRONG_NAME_RADIUS_M):
        score = _name_score(name, known_name, distance)
        if score is not None:
            near.append((-score, distance, known))
    if near:
        return min(near, key=lambda c: (c[0], c[1]))[2]

    far = []
    for _, known_name, known in index.nearby(latitude, longitude, _MISPLACED_RADIUS_M):
        if known.get("state") != state or not known_name:
            continue
        city = _normalize_name(known.get("city"))
        if known_name == name or (city and known_name == f"{name} {city}"):
            far.append(known)
    return far[0] if len(far) == 1 else None


def _blank(value) -> bool:
    return value is None or (isinstance(value, str) and not value.strip())


def _missing(known: dict, fields: dict) -> dict:
    """Only the fields the known church doesn't already have. The list endpoint omits
    serviceTimes (summary DTO), but serviceSummary is non-null exactly when there are
    some."""
    wanted = {}
    for key, value in fields.items():
        current = known.get("serviceSummary") if key == "serviceTimes" else known.get(key)
        if _blank(current):
            wanted[key] = value
    return wanted


def known_index(existing: Iterable[dict]) -> SpatialIndex:
    index = SpatialIndex()
    for church in existing:
        if church.get("id") is not None:
            index.add(church)
    return index


def merge_fills(matches: Iterable[Tuple[dict, dict]], fill_keys: Iterable[str]) -> Tuple[List[dict], dict]:
    """(known church, its proposed fields) pairs → (fills, stats). Several sources landing
    on one church are merged; a field they disagree on is dropped rather than guessed.
    Each fill is {"id": ..., <only the fields that church is missing>}. Shared with
    wikidata_fill.py."""
    merged: Dict[int, dict] = {}
    conflicts: Dict[int, set] = {}
    known_by_id: Dict[int, dict] = {}
    for known, fields in matches:
        church_id = known["id"]
        known_by_id[church_id] = known
        target = merged.setdefault(church_id, {})
        for key, value in fields.items():
            if key in target and target[key] != value:
                conflicts.setdefault(church_id, set()).add(key)
            target.setdefault(key, value)

    fills = []
    for church_id, fields in merged.items():
        for key in conflicts.get(church_id, ()):
            fields.pop(key)
        wanted = _missing(known_by_id[church_id], fields)
        if wanted:
            fills.append({"id": church_id, **wanted})

    stats = {"conflictingFields": sum(len(keys) for keys in conflicts.values()), "churchesToFill": len(fills)}
    for key in fill_keys:
        stats[f"fill.{key}"] = sum(1 for fill in fills if key in fill)
    return fills, stats


def build_fills(elements: Iterable[Tuple[str, dict]], existing: Iterable[dict]) -> Tuple[List[dict], dict]:
    """(fills, stats). Each fill is {"id": ..., <only the fields to set>}."""
    index = known_index(existing)
    stats = {"osmChurches": 0, "withFillableTags": 0, "matched": 0, "unmatched": 0}
    matches = []

    for state, element in elements:
        if not is_church(element):
            continue
        stats["osmChurches"] += 1
        if not osm_fields(element):
            continue
        stats["withFillableTags"] += 1
        known = match_known(element, state, index)
        if known is None:
            stats["unmatched"] += 1
            continue
        stats["matched"] += 1
        matches.append((known, osm_fields(element, known.get("denomination"))))

    fills, merge_stats = merge_fills(matches, TEXT_FIELDS + ("serviceTimes",))
    return fills, {**stats, **merge_stats}


# --- io -----------------------------------------------------------------------------

def _read_cache(cache_path: Optional[str]) -> Dict[str, List[dict]]:
    """{state: elements}. Also reads the original flat [[state, element], ...] format."""
    if not cache_path or not os.path.exists(cache_path):
        return {}
    with open(cache_path) as f:
        data = json.load(f)
    if isinstance(data, dict):
        return data
    by_state: Dict[str, List[dict]] = {}
    for state, element in data:
        by_state.setdefault(state, []).append(element)
    return by_state


def _write_cache(cache_path: str, by_state: Dict[str, List[dict]]) -> None:
    # Write-then-rename, so a crash mid-write never leaves a truncated cache behind.
    tmp_path = f"{cache_path}.tmp"
    with open(tmp_path, "w") as f:
        json.dump(by_state, f)
    os.replace(tmp_path, cache_path)


def load_elements(
    session: requests.Session,
    states: Iterable[str],
    cache_path: Optional[str],
    sleep: Callable[[float], None] = time.sleep,
) -> List[Tuple[str, dict]]:
    """Fetches state by state, saving the cache after each one — the public Overpass
    instance routinely 504s, and a state that exhausts its retries would otherwise throw
    away every state already downloaded. Re-running the same command resumes with the
    states still missing."""
    states = list(states)
    by_state = _read_cache(cache_path)
    missing = [state for state in states if state not in by_state]
    if by_state:
        logger.info(
            "overpass cache %s: %d states cached, %d to fetch", cache_path, len(states) - len(missing), len(missing)
        )
    for index, state in enumerate(missing):
        if index:
            sleep(OVERPASS_STATE_DELAY_S)
        by_state[state] = [element for _, element in fetch_all_elements(session, [state])]
        if cache_path:
            _write_cache(cache_path, by_state)
    return [(state, element) for state in states for element in by_state[state]]


def submit_fills(
    config: BulkImportConfig, session: requests.Session, batch: List[dict], source: str = SOURCE_NAME
) -> int:
    response = session.post(
        f"{config.server_base_url.rstrip('/')}{FILL_ENDPOINT}",
        json={"churches": batch, "source": source},
        headers={BULK_IMPORT_HEADER: config.bulk_import_api_key},
        timeout=120,
    )
    response.raise_for_status()
    return response.json().get("updated", 0)


def log_samples(fills: List[dict], existing_by_id: Dict[int, dict], count: int = 25) -> None:
    for fill in random.sample(fills, min(count, len(fills))):
        known = existing_by_id.get(fill["id"], {})
        logger.info(
            "  #%s %s (%s, %s) <- %s",
            fill["id"], known.get("name"), known.get("city"), known.get("state"),
            {k: v for k, v in fill.items() if k != "id"},
        )


def run(
    config: Optional[BulkImportConfig] = None,
    session: Optional[requests.Session] = None,
    states: Iterable[str] = STATE_CODES,
    cache_path: Optional[str] = None,
    dry_run: bool = False,
    batch_size: int = DEFAULT_BATCH_SIZE,
    batch_delay_s: float = DEFAULT_BATCH_DELAY_S,
) -> dict:
    config = config or BulkImportConfig.from_env()
    session = session or requests.Session()

    elements = load_elements(session, states, cache_path)
    existing = fetch_all_existing_churches(config.server_base_url, session)
    fills, stats = build_fills(elements, existing)
    logger.info("osm fill plan: %s", stats)

    if dry_run:
        log_samples(fills, {c.get("id"): c for c in existing})
        return {**stats, "updated": 0}

    updated = 0
    for start in range(0, len(fills), batch_size):
        batch = fills[start:start + batch_size]
        updated += submit_fills(config, session, batch)
        logger.info("osm fill progress: %d/%d sent, %d updated", start + len(batch), len(fills), updated)
        if start + batch_size < len(fills):
            time.sleep(batch_delay_s)

    result = {**stats, "updated": updated}
    logger.info("osm fill run: %s", result)
    return result


def main(argv: Optional[List[str]] = None) -> dict:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--dry-run", action="store_true", help="match and report, send nothing")
    parser.add_argument("--cache", help="JSON file to save/reuse the Overpass download")
    parser.add_argument("--states", help="comma-separated state codes (default: all 50 + DC)")
    args = parser.parse_args(argv)
    states = [s.strip().upper() for s in args.states.split(",")] if args.states else STATE_CODES
    return run(states=states, cache_path=args.cache, dry_run=args.dry_run)


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    main()
