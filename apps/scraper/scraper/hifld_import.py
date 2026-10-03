"""One-time/occasional bulk seed from the live HIFLD "All Places of Worship (IRS 2024)"
ArcGIS feature service — the direct government source the Kaggle "us-places-of-worship"
dataset (bulk_import.py) was itself mirrored from, at an earlier and much smaller
vintage: 49,328 total rows there vs. 254,742 in the live service (confirmed 2026-09-27
via a direct row-count query against the service below).

A separate module from bulk_import.py, not a variant of it, because the live service
dropped the Kaggle mirror's pre-classified `subtype`/`denom`/`cath`/`prot` columns
entirely. What's left is a rawer, direct pull from the IRS Exempt Organizations
Business Master File (EIN, AFFILIATION, ACTIVITY, ORGANIZATION, NTEE_CD — IRS filing
codes, not a religion or denomination field), and it mixes in plenty of
non-congregation entities the Kaggle version had already filtered out — a same-state
sample turned up a Catholic high school, a "Council of Churches," a Bible society, and
a parachurch youth ministry alongside real churches, none flagged by any column.
Getting actual congregations back out needs classification built on the real IRS EO
BMF code definitions (Publication 5926, irs.gov/pub/irs-pdf/p5926.pdf), not
bulk_import.py's simpler name-keyword-on-top-of-a-subtype-flag filter — see
`_CONGREGATION_AFFILIATIONS` and `_EXCLUDE_NAME_KEYWORDS` below.

**Known ceiling (documented by the IRS itself, not inferred):** this data is built from
the IRS Exempt Organizations Business Master File, which explicitly excludes "Churches
... that are not required under the Code to file an application and that have not filed
an application for recognition of exemption." Churches get automatic 501(c)(3) status
without ever applying (IRC 508(c)(1)(A)), and many small/independent congregations never
do. No amount of filtering recovers those rows — they were never in the source data. See
docs/SCRAPER.md "HIFLD live import" for the full source comparison this was decided
against (Wikidata, OpenStreetMap, Google Places).

Deliberately NOT wired into pipeline.run() or the recurring Lambda, same as
bulk_import.py — run manually:

    python -m scraper.hifld_import
"""

import logging
import re
import time
from typing import Callable, List, Optional

import requests

from .bulk_import import DEFAULT_BATCH_DELAY_S, DEFAULT_BATCH_SIZE, dedupe_within_batch, submit_batch
from .config import BulkImportConfig
from .dedup import _normalize_key, existing_keys
from .existing_churches import fetch_all_existing_churches

logger = logging.getLogger(__name__)

HIFLD_QUERY_URL = (
    "https://services.arcgis.com/XG15cJAlne2vxtgt/ArcGIS/rest/services/"
    "All_Places_Of_Worship__HiFLD_Open_/FeatureServer/42/query"
)
# The layer's own maxRecordCount (confirmed via its ?f=json metadata) — requesting more
# than this per page is silently capped by the server, so this *is* the page size, not
# just a default.
HIFLD_PAGE_SIZE = 2000
# Real ArcGIS/shapefile-style field names are truncated to 10 characters —
# "AFFILIATIO", not "AFFILIATION" (confirmed against the live service; the untruncated
# name is rejected outright with a 400). X/Y are deliberately *not* here: they aren't
# queryable attributes at all, they live in each feature's separate `geometry` block
# (see fetch_all_records) and come back in the service's native Web Mercator (meters,
# wkid 3857) unless `outSR=4326` is requested — confirmed by inspecting real values
# (e.g. x=-7908528 with the default SR vs. the correct x=-71.04 degrees with
# outSR=4326 for the same "ST GEORGE CATHEDRAL" row).
HIFLD_OUT_FIELDS = "NAME,STREET,CITY,STATE,ZIP,AFFILIATIO"
HIFLD_OUT_SR = 4326

# AFFILIATION CODE (IRS Publication 5926, "EO BMF Information Sheet"): 1/2 = central or
# intermediate organization of a national/regional grouping (no group exemption),
# 6/7 = group-ruling parent/intermediate, 8 = group-ruling parent that IS a church or
# 501(c)(1) (a diocese, a denominational headquarters). None of those five are an
# individual congregation — they're the organizational body *above* one, which is
# exactly how entries like "Maine Council of Churches" and "General Council of
# Assemblies of God" end up in this raw pool. Only 3 (independent, unaffiliated with
# any national/regional grouping) and 9 (subordinate — an individual congregation under
# someone else's group ruling, e.g. a parish under its diocese, or a UMC congregation
# under its conference) are real, individual local congregations. A missing/blank
# AFFILIATION isn't treated as a fail — plenty of real churches have it blank (spot
# checks turned up "FIRST BAPTIST CHURCH IN PORTLAND MAINE" with no AFFILIATION at
# all) — so this only excludes a row when the code is present AND says "organizational
# body," never when the signal is simply absent.
_CONGREGATION_AFFILIATIONS = {"3", "9"}

# This dataset has no subtype/religion column at all (unlike the Kaggle mirror's
# `subtype`) — name-keyword filtering is the *only* signal here, not a safety net on
# top of one, so it has to do more work than bulk_import.py's equivalent lists.
_EXCLUDE_NAME_KEYWORDS = (
    # Other faiths.
    "mosque", "synagogue", "gurdwara", "masjid", "shul", "temple",
    # Non-congregation entities confirmed present via real spot checks of this raw
    # pool: a Catholic high school, a diocesan/denominational body, a Bible society, a
    # parachurch youth-evangelism ministry, a denominational "General Council," and a
    # regional "Conference" (a UCC governing body, not a congregation) — none flagged
    # by AFFILIATION alone. "Conference"/"General Council" are bare rather than
    # phrase-matched ("conference of") since real individual congregations
    # essentially never use either word in their own name.
    "school", "academy", "college", "university", "seminary", "institute for",
    "council of churches", "association of churches", "convention of",
    "general council", "diocese of", "archdiocese", "conference", "synod of",
    "presbytery of", "foundation", "cemetery", "memorial park", "bible society",
    "evangelism fellowship",
)
_INCLUDE_NAME_KEYWORDS = (
    "church", "chapel", "cathedral", "parish", "ministries", "ministry",
    "congregation", "fellowship", "tabernacle", "basilica",
    # Real congregations confirmed missed by the list above in a 2026-09-30 dry run
    # against the live service (76k rows failed only the include check) — e.g.
    # "EMMANUEL ASSEMBLY OF GOD", "IGLESIA BETHESDA INC", "MINISTERIO APOSTOLICO ...",
    # "PISCATAQUIS RIVER CHRISTIAN CENTER", "LITTLE HOUSE OF PRAYER INC". Spanish and
    # Portuguese congregations essentially never say "church" in their filed name.
    "assembly of god", "iglesia", "ministerio", "igreja", "christian center",
    "house of prayer",
)

# Best-effort denomination guess from the organization's own filed name — there is no
# denom/cath/prot column here at all (unlike the Kaggle mirror). Each pattern maps to one
# DENOMINATIONS label (church.model.ts), using the same buckets as bulk_import.py's
# DENOMINATION_CODE_MAP (Nazarene/C&MA/Vineyard → Evangelical; Church of God, Apostolic,
# Foursquare, COGIC → Pentecostal; Wesleyan, AME/CME → Methodist; Brethren, Mennonite,
# Reformed, UCC, Disciples, Quaker → Other), so a name-matched row and a code-mapped one
# agree. A None label leaves the church blank: its words would otherwise hit a wrong bucket.
#
# Order matters — the first match wins, and many real names contain another body's words:
#   "African Methodist Episcopal" is Methodist, not Episcopal;
#   "United Church of Christ" is Congregational (Other), not Church of Christ;
#   "Reformed Presbyterian" is Presbyterian, not Reformed;
#   "New Apostolic" and "Apostolic Christian" are their own bodies, not Pentecostal;
#   "Church of God (Seventh Day)" is Sabbatarian, not Pentecostal;
#   "Evangelical Lutheran", "Evangelical Free" etc. take the more specific label.
# Patterns match whole words (\b) on the lowercased name, so "ame" never hits "James".
_DENOMINATION_NAME_PATTERNS = tuple((re.compile(pattern), label) for pattern, label in (
    (r"\barmenian apostolic\b|\bassyrian\b", None),  # Oriental/Assyrian bodies: no honest bucket
    (r"\bunited church of christ\b|\bucc\b", "Other"),
    (r"\bnew apostolic\b|\bapostolic christian\b", "Other"),
    (r"\bchurch of god\b.*\bseventh[- ]day\b|\bseventh[- ]day church of god\b"
     r"|\biglesia de dios\b.*\bs[eé]ptimo d[ií]a\b", "Other"),
    (r"\bcatholic\b|\bcat[oó]lica\b", "Catholic"),
    (r"\borthodox\b|\bortodoxa\b", "Eastern Orthodox"),
    (r"\blatter[- ]day\b|\bmormon\b", "Latter-day Saint (LDS)"),
    (r"\badventist\b|\badventista\b", "Seventh-day Adventist"),
    (r"\bmethodist\b|\bmetodista\b|\bwesleyan\b|\ba\.? ?m\.? ?e\.?( ?z\.?)?\b|\bc\.? ?m\.? ?e\.?\b",
     "Methodist"),
    (r"\blutheran\b|\bluterana\b", "Lutheran"),
    (r"\bpresbyterian\b|\bpresbiteriana\b", "Presbyterian"),
    (r"\bbaptist\b|\bbautista\b|\bbatista\b", "Baptist"),
    (r"\bepiscopal\b|\banglican\b", "Episcopal/Anglican"),
    (r"\bchurch(es)? of christ\b|\biglesia de cristo\b", "Church of Christ"),
    (r"\bpentecostal\b|\bassembl(y|ies) of god\b|\basamblea de dios\b|\bfoursquare\b"
     r"|\bchurch of god\b|\biglesia de dios\b|\bcogic\b|\bapost[oó]lic[oa]?\b",
     "Pentecostal / Assembly of God"),
    (r"\bnon-?denominational\b", "Non-denominational"),
    (r"\bnazarene\b|\bnazareno\b|\bchristian (and|&) missionary alliance\b|\balliance church\b"
     r"|\bvineyard\b|\bcalvary chapel\b|\bevangelical\b|\bevang[eé]lica\b",
     "Evangelical"),
    (r"\bbrethren\b|\bmennonite\b|\bamish\b|\breformed\b|\bcongregational\b"
     r"|\bdisciples of christ\b|\bquaker\b|\bfriends meeting\b|\bunitarian\b", "Other"),
))


def _clean(value) -> Optional[str]:
    """Values here come straight from ArcGIS's JSON response — plain strings or None,
    no pandas NaN or dataset-specific null sentinel to normalize (unlike
    bulk_import.py's _clean, which handles the Kaggle CSV's own conventions)."""
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _zip_code(raw) -> Optional[str]:
    """ZIP arrives as either a plain 5-digit string or a ZIP+4 ("12345-6789")."""
    cleaned = _clean(raw)
    if cleaned is None:
        return None
    digits = cleaned.split("-")[0].strip()
    return digits.zfill(5) if digits.isdigit() else None


def _denomination_from_name(name: Optional[str]) -> Optional[str]:
    """Left None (not "Other") when nothing matches — same convention as
    bulk_import.py's denomination handling: an honest blank beats a guessed-wrong
    label. The client's own denomStyle (church-display.util.ts) already treats a
    blank denomination as unclassified rather than forcing it into "Other" — a null
    here just means nobody's classified this church yet, not that it's been checked
    and doesn't fit any tradition.
    """
    if not name:
        return None
    lower = name.lower()
    for pattern, denomination in _DENOMINATION_NAME_PATTERNS:
        if pattern.search(lower):
            return denomination
    return None


def is_church(row: dict) -> bool:
    affiliation = _clean(row.get("AFFILIATIO"))
    if affiliation is not None and affiliation not in _CONGREGATION_AFFILIATIONS:
        return False

    name = (_clean(row.get("NAME")) or "").lower()
    if any(keyword in name for keyword in _EXCLUDE_NAME_KEYWORDS):
        return False
    return any(keyword in name for keyword in _INCLUDE_NAME_KEYWORDS)


def has_coordinates(row: dict) -> bool:
    """Rows without coordinates are skipped outright, not sent coordinate-less: the
    server falls back to the paid Google Geocoding API for any church created without
    lat/lng (ChurchService.applyDto), and at this dataset's volume that fallback is real
    money. HIFLD already geocodes nearly every row, so this loses very little."""
    return row.get("X") is not None and row.get("Y") is not None


def to_church_dto_shape(row: dict) -> dict:
    """Same wire-format responsibility as bulk_import.to_church_dto_shape: camelCase,
    matching server/.../dto/ChurchDto.java exactly. `phone` is always None — this
    dataset has no telephone field at all, unlike the Kaggle mirror's `telephone`
    column."""
    name = _clean(row.get("NAME"))
    return {
        "name": name,
        "denomination": _denomination_from_name(name),
        "address": _clean(row.get("STREET")),
        "city": _clean(row.get("CITY")),
        "state": _clean(row.get("STATE")),
        "zipCode": _zip_code(row.get("ZIP")),
        "phone": None,
        "latitude": row.get("Y"),
        "longitude": row.get("X"),
    }


HIFLD_MAX_RETRIES = 5
HIFLD_RETRY_BACKOFF_S = 2.0


def _fetch_page(
    session: requests.Session,
    offset: int,
    sleep: Callable[[float], None] = time.sleep,
) -> dict:
    """One page, with retries — ~128 sequential requests against a shared, third-party
    ArcGIS instance over several minutes is exactly the kind of run where a single
    transient connection drop shouldn't lose all the work already done (confirmed
    against the live service: the very first request of a full run once failed with
    `RemoteDisconnected`, a plain network blip with no retry to recover from, which
    killed the whole run before any data had even been written anywhere)."""
    last_error: Optional[Exception] = None

    for attempt in range(HIFLD_MAX_RETRIES):
        try:
            response = session.get(
                HIFLD_QUERY_URL,
                params={
                    "where": "1=1",
                    "outFields": HIFLD_OUT_FIELDS,
                    "outSR": HIFLD_OUT_SR,
                    "resultOffset": offset,
                    "resultRecordCount": HIFLD_PAGE_SIZE,
                    "f": "json",
                },
                timeout=60,
            )
            response.raise_for_status()
            return response.json()
        except requests.exceptions.RequestException as error:
            last_error = error
            sleep(HIFLD_RETRY_BACKOFF_S * (attempt + 1))

    raise RuntimeError(f"HIFLD query failed after {HIFLD_MAX_RETRIES} attempts at offset {offset}") from last_error


def fetch_all_records(
    session: Optional[requests.Session] = None,
    sleep: Callable[[float], None] = time.sleep,
) -> List[dict]:
    """Pages through the ArcGIS query endpoint via resultOffset/resultRecordCount
    (the layer's documented pagination mechanism) rather than the Overpass-style
    bulk-download-and-filter-locally approach — this table is small enough (254,742
    rows as of 2026-09) that ~128 paginated requests against ArcGIS's own
    infrastructure is simpler than standing up a separate download+filter pipeline."""
    session = session or requests.Session()
    records: List[dict] = []
    offset = 0

    while True:
        body = _fetch_page(session, offset, sleep)
        # ArcGIS returns HTTP 200 even for a query error (e.g. a bad field name) — the
        # error is only visible in the JSON body, so a bare raise_for_status() plus
        # `.get("features", [])` would silently "succeed" with zero records. Found this
        # the hard way testing against the live service: a since-fixed bad field name
        # produced exactly that silent zero-record result.
        if "error" in body:
            raise RuntimeError(f"HIFLD query failed: {body['error']}")
        features = body.get("features", [])
        # `X`/`Y` aren't real attributes — they live in each feature's separate
        # `geometry` block — so they're merged into the attributes dict under those
        # keys here, giving to_church_dto_shape a single flat dict to read `row.get
        # ("X")`/`row.get("Y")` from. A null geometry (ungeocoded row) just leaves
        # both keys absent, same as a missing attribute.
        for feature in features:
            attributes = feature["attributes"]
            geometry = feature.get("geometry")
            if geometry:
                attributes["X"] = geometry.get("x")
                attributes["Y"] = geometry.get("y")
            records.append(attributes)

        if len(features) < HIFLD_PAGE_SIZE:
            break
        offset += HIFLD_PAGE_SIZE

    return records


def run(
    config: Optional[BulkImportConfig] = None,
    session: Optional[requests.Session] = None,
    batch_size: int = DEFAULT_BATCH_SIZE,
    batch_delay_s: float = DEFAULT_BATCH_DELAY_S,
) -> dict:
    """Full run: fetch (paginated) -> filter -> map -> dedupe (internal, then against
    the server) -> ingest in batches via the same trusted bulk-import endpoint
    bulk_import.py uses. Resumable by construction, same as bulk_import.py — a re-run
    after a partial failure just dedupes against whatever already made it in.
    """
    config = config or BulkImportConfig.from_env()
    session = session or requests.Session()

    raw_records = fetch_all_records(session)
    church_records = [r for r in raw_records if is_church(r)]
    geocoded_records = [r for r in church_records if has_coordinates(r)]

    mapped = [to_church_dto_shape(r) for r in geocoded_records]
    deduped = dedupe_within_batch(mapped)

    existing = fetch_all_existing_churches(config.server_base_url, session)
    known = existing_keys(existing)
    new_churches = [c for c in deduped if _normalize_key(c.get("name"), c.get("zipCode")) not in known]

    created = 0
    for start in range(0, len(new_churches), batch_size):
        batch = new_churches[start:start + batch_size]
        created += submit_batch(config, session, batch)
        logger.info("hifld import progress: %d/%d ingested", start + len(batch), len(new_churches))
        if start + batch_size < len(new_churches):
            time.sleep(batch_delay_s)  # real Geocoding API quota is burned per row server-side

    result = {
        "totalRecords": len(raw_records),
        "churchRecords": len(church_records),
        "withCoordinates": len(geocoded_records),
        "afterInternalDedup": len(deduped),
        "new": len(new_churches),
        "created": created,
    }
    logger.info("hifld import run: %s", result)
    return result


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    run()
