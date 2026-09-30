"""One-time/occasional bulk seed from the Kaggle "us-places-of-worship" dataset
(mexwell/us-places-of-worship), filtered to Christian churches (Prompt 7).

Deliberately NOT wired into pipeline.run() or the recurring Lambda — run manually:

    python -m scraper.bulk_import

See docs/SCRAPER.md "Bulk import" for the write-up of this dataset's real schema
(the Step 1 findings below were recorded by actually downloading and inspecting it,
not guessed) and the filtering/mapping/ingestion decisions this module makes.
"""

import logging
import time
from typing import Iterable, List, Optional

import kagglehub
import pandas as pd
import requests

from .config import BulkImportConfig
from .dedup import _normalize_key, existing_keys
# Re-exported (fetch_all_existing_churches, DEFAULT_PAGE_SIZE) so `from scraper.bulk_import
# import fetch_all_existing_churches` keeps working — this module used to define it
# itself; it now lives in existing_churches.py, shared with pipeline.py. See that
# module's docstring for why the live scraper needed this fix.
from .existing_churches import DEFAULT_PAGE_SIZE, fetch_all_existing_churches

logger = logging.getLogger(__name__)

DATASET_HANDLE = "mexwell/us-places-of-worship"
DATASET_FILENAME = "all_places_of_worship.csv"

# Step 1 findings (recorded 2026-09, dataset version 1, 49,328 rows):
#   columns: objectid, id, name, telephone, address, address2, city, state, zip,
#   zipp4, county, fips, directions, emergtitle, emergtel, emergext, contdate,
#   conthow, geodate, geohow, hsipthemes, naicscode, naicsdescr, geolinkid, x, y,
#   st_vendor, st_version, geoprec, phoneloc, qc_qa, state_id, subtype, denom,
#   members, attendance, loc_type, prot, cath
#
#   `subtype` is a clean top-level religion category: CHRISTIAN (42,697), JUDAIC
#   (3,256), BUDDHIST (1,711), MUSLIM (1,254), HINDU (282), OTHER (128) — no row is
#   missing it. `x`/`y` are longitude/latitude (GIS convention, confirmed against
#   real US coordinate ranges). `zip` is stored as an int, which silently drops
#   leading zeros for ~4,051 rows (New England zips) — must re-zero-pad to 5 digits.
#   The dataset's own null convention is the literal string "NOT AVAILABLE", not
#   NaN, for every text column checked except `telephone` (real NaN, ~900 rows).
#   No Jehovah's Witness entries exist in this dataset (checked: 0 name matches for
#   "witness"; the handful of "JEHOVAH ..." names are Baptist churches). LDS is
#   well represented (2,117 rows) and correctly categorized under subtype=CHRISTIAN,
#   matching the brief's "inclusive of LDS/JW" intent for the group that's present.
CHRISTIAN_SUBTYPE = "CHRISTIAN"

# Applied on top of `subtype` regardless of how clean it looks here, per Prompt 7 —
# a safety net against a mislabeled row (e.g. subtype=CHRISTIAN with an obviously
# non-Christian name from a data-entry error), not a replacement for the category
# column.
_EXCLUDE_NAME_KEYWORDS = ("mosque", "synagogue", "temple", "gurdwara", "masjid", "shul")
_INCLUDE_NAME_KEYWORDS = ("church", "chapel", "cathedral", "parish", "ministries", "ministry")

# The dataset's `denom` column is a raw abbreviation code (e.g. "BAPT", "SBC", "AME"),
# not the site's DENOMINATIONS taxonomy (apps/client/src/app/models/church.model.ts) —
# left unmapped here in the initial Prompt 7 pass, the denomination filter matched
# almost nothing against the real import. Mirrors V8__normalize_denominations_and_
# case_insensitive_filters.sql's backfill exactly, so a fresh import and the already-
# backfilled rows agree; keep the two in sync if this map changes. Best-effort
# taxonomy alignment (see that migration's comment for the per-code reasoning), not an
# authoritative one. Codes not in this map pass through unchanged, except "UNK" (the
# dataset's own "unknown" marker) which clears to None like a genuinely blank value.
DENOMINATION_CODE_MAP = {
    # Baptist
    "BAPT": "Baptist", "ABC": "Baptist", "SBC": "Baptist", "NBC": "Baptist",
    "MISSBAPT": "Baptist", "BGC": "Baptist", "CBF": "Baptist", "GBA": "Baptist",
    "BBF": "Baptist", "ABC-NBC": "Baptist", "NBC-ABC": "Baptist",
    # Methodist (incl. historically-Black Methodist bodies)
    "AME": "Methodist", "AMEZ": "Methodist", "UMC": "Methodist", "CME": "Methodist",
    "WES": "Methodist",
    # Latter-day Saint
    "LDS": "Latter-day Saint (LDS)",
    # Presbyterian / Reformed-Presbyterian
    "PCA": "Presbyterian", "PCUSA": "Presbyterian", "PRESB": "Presbyterian",
    "EPC": "Presbyterian", "KPC": "Presbyterian", "KPCA": "Presbyterian",
    # Episcopal/Anglican
    "EPIS": "Episcopal/Anglican", "ANGLICAN": "Episcopal/Anglican",
    # Lutheran
    "ELCA": "Lutheran", "LCMS": "Lutheran", "LUTH": "Lutheran",
    # Church of Christ
    "COC": "Church of Christ", "COC ACA": "Church of Christ",
    # Non-denominational
    "NONDENOM": "Non-denominational",
    # Pentecostal / Assembly of God (incl. Holiness-Pentecostal bodies)
    "AG": "Pentecostal / Assembly of God", "COG": "Pentecostal / Assembly of God",
    "COGA": "Pentecostal / Assembly of God", "COGIC": "Pentecostal / Assembly of God",
    "4SQ": "Pentecostal / Assembly of God", "PENT": "Pentecostal / Assembly of God",
    "APOSTOLIC": "Pentecostal / Assembly of God", "UPC": "Pentecostal / Assembly of God",
    "PAW": "Pentecostal / Assembly of God", "IPHC": "Pentecostal / Assembly of God",
    # Evangelical (incl. Evangelical-Holiness bodies with no closer bucket)
    "CAL": "Evangelical", "VINE": "Evangelical", "EF": "Evangelical",
    "EVAN": "Evangelical", "CMA": "Evangelical", "EC": "Evangelical",
    "NAZ": "Evangelical", "CCCC": "Evangelical",
    # Seventh-day Adventist
    "SDA": "Seventh-day Adventist",
    # No honest match in the current taxonomy — bucketed as Other rather than guessed.
    "CRC": "Other", "RCA": "Other", "UCC": "Other", "MCC/UCC": "Other",
    "DOC": "Other", "CHRISTIAN": "Other", "BRETHREN": "Other", "QUAKER": "Other",
    "RELSCI": "Other", "FCA": "Other", "PNB": "Other",
    # Dataset's own "unknown" marker — not a real denomination.
    "UNK": None,
}


def _normalize_denomination(raw: Optional[str]) -> Optional[str]:
    if raw is None:
        return None
    mapped = DENOMINATION_CODE_MAP.get(raw.upper(), raw)
    return mapped


def _denomination_for_row(row) -> Optional[str]:
    """The dataset encodes Catholic/Orthodox parishes via the separate `cath` boolean
    flag column, not `denom` — `denom` is "NOT AVAILABLE" for virtually every one of
    them (confirmed: 0 rows anywhere in the dataset have "CATH" in `denom`). Relying on
    `denom` alone, as the initial Prompt 7 pass did, silently left ~6,877 Catholic
    churches' denomination as None. A `cath="Y"` row whose name says "Orthodox" is
    genuinely Eastern Orthodox (Malankara/Greek/Holy Trinity, checked individually);
    every other `cath="Y"` row is Catholic. "Orthodox Presbyterian" rows are prot="Y"/
    cath="N" in this dataset, so they never hit this branch and correctly keep falling
    through to their own PCA/etc. `denom` code below.
    """
    if _clean(row.get("cath")) == "Y":
        name = (_clean(row.get("name")) or "").lower()
        return "Eastern Orthodox" if "orthodox" in name else "Catholic"
    return _normalize_denomination(_clean(row.get("denom")))


NOT_AVAILABLE = "NOT AVAILABLE"
DEFAULT_BATCH_SIZE = 100
DEFAULT_BATCH_DELAY_S = 1.0
# DEFAULT_PAGE_SIZE comes from the existing_churches import above.
BULK_IMPORT_ENDPOINT = "/api/scraper/bulk-import"
BULK_IMPORT_HEADER = "X-Bulk-Import-Api-Key"


def _clean(value) -> Optional[str]:
    """The dataset's null convention is the literal string "NOT AVAILABLE" (not NaN)
    for almost every column here — this normalizes both to None."""
    if value is None:
        return None
    if isinstance(value, float) and pd.isna(value):
        return None
    text = str(value).strip()
    if not text or text == NOT_AVAILABLE:
        return None
    return text


def download_dataset() -> str:
    """Downloads (or reuses kagglehub's local cache for) the dataset; returns the CSV path."""
    dataset_dir = kagglehub.dataset_download(DATASET_HANDLE)
    return f"{dataset_dir}/{DATASET_FILENAME}"


def load_rows(csv_path: str) -> pd.DataFrame:
    return pd.read_csv(csv_path)


def is_christian(row) -> bool:
    subtype = _clean(row.get("subtype"))
    name = (_clean(row.get("name")) or "").lower()

    if any(keyword in name for keyword in _EXCLUDE_NAME_KEYWORDS):
        return False

    if subtype is not None:
        return subtype.upper() == CHRISTIAN_SUBTYPE

    # Not observed in the real dataset (every row has a subtype) — a name-based net
    # rather than silently dropping the row if that ever changes.
    return any(keyword in name for keyword in _INCLUDE_NAME_KEYWORDS)


def filter_christian(df: pd.DataFrame) -> pd.DataFrame:
    return df[df.apply(is_christian, axis=1)]


def _zip_code(row) -> Optional[str]:
    zip_value = row.get("zip")
    if zip_value is None or (isinstance(zip_value, float) and pd.isna(zip_value)):
        return None
    # `zip` is read as an int by pandas, which drops leading zeros for New England
    # zips (~4,051 rows) — re-zero-pad to the standard 5 digits.
    return str(int(zip_value)).zfill(5)


def to_church_dto_shape(row) -> dict:
    """Same wire-format responsibility as pipeline._to_church_dto_shape: camelCase,
    matching server/.../dto/ChurchDto.java exactly. `x`/`y` are longitude/latitude —
    supplying both here means ChurchService.create trusts these coordinates directly
    rather than re-geocoding hundreds of thousands of addresses for no reason."""
    return {
        "name": _clean(row.get("name")),
        "denomination": _denomination_for_row(row),
        "address": _clean(row.get("address")),
        "city": _clean(row.get("city")),
        "state": _clean(row.get("state")),
        "zipCode": _zip_code(row),
        "phone": _clean(row.get("telephone")),
        "latitude": row.get("y"),
        "longitude": row.get("x"),
    }


def map_rows(df: pd.DataFrame) -> List[dict]:
    return [to_church_dto_shape(row) for _, row in df.iterrows()]


def dedupe_within_batch(churches: Iterable[dict]) -> List[dict]:
    """The dataset itself has internal duplicate name+zip rows (65 in version 1) —
    drop those before ever comparing against the server, via the same normalized-key
    matching dedup.py uses against existing Church rows."""
    seen = set()
    deduped = []
    for church in churches:
        key = _normalize_key(church.get("name"), church.get("zipCode"))
        if key in seen:
            continue
        seen.add(key)
        deduped.append(church)
    return deduped


def submit_batch(
    config: BulkImportConfig,
    session: requests.Session,
    batch: List[dict],
    source: Optional[str] = None,
    total_new: Optional[int] = None,
) -> int:
    # source/total_new are optional progress-reporting fields (see
    # BulkImportStatusService on the server) — omitted entirely rather than sent as
    # null when not given, so a caller not participating in progress reporting (or
    # an older server) sees the exact same body this endpoint has always accepted.
    body: dict = {"churches": batch}
    if source is not None:
        body["source"] = source
    if total_new is not None:
        body["totalNew"] = total_new

    response = session.post(
        f"{config.server_base_url.rstrip('/')}{BULK_IMPORT_ENDPOINT}",
        json=body,
        headers={BULK_IMPORT_HEADER: config.bulk_import_api_key},
        # 120s, not 60 — same reasoning as existing_churches.py's fetch timeout:
        # confirmed live that a batch of 100 inserts occasionally took longer than
        # 60s against a growing production table.
        timeout=120,
    )
    response.raise_for_status()
    return response.json().get("created", len(batch))


def run(
    config: Optional[BulkImportConfig] = None,
    session: Optional[requests.Session] = None,
    batch_size: int = DEFAULT_BATCH_SIZE,
    batch_delay_s: float = DEFAULT_BATCH_DELAY_S,
) -> dict:
    """Full run: download -> filter -> map -> dedupe (internal, then against the
    server) -> ingest in batches. Resumable by construction — a re-run after a
    partial failure just dedupes against whatever already made it in, rather than
    needing a separate checkpoint file.
    """
    config = config or BulkImportConfig.from_env()
    session = session or requests.Session()

    csv_path = download_dataset()
    df = load_rows(csv_path)
    christian_df = filter_christian(df)

    mapped = map_rows(christian_df)
    deduped = dedupe_within_batch(mapped)

    existing = fetch_all_existing_churches(config.server_base_url, session)
    known = existing_keys(existing)
    new_churches = [c for c in deduped if _normalize_key(c.get("name"), c.get("zipCode")) not in known]

    created = 0
    for start in range(0, len(new_churches), batch_size):
        batch = new_churches[start:start + batch_size]
        created += submit_batch(config, session, batch, source="kaggle", total_new=len(new_churches))
        logger.info("bulk import progress: %d/%d ingested", start + len(batch), len(new_churches))
        if start + batch_size < len(new_churches):
            time.sleep(batch_delay_s)  # real Geocoding API quota is burned per row server-side

    result = {
        "totalRows": len(df),
        "christianRows": len(christian_df),
        "afterInternalDedup": len(deduped),
        "new": len(new_churches),
        "created": created,
    }
    logger.info("bulk import run: %s", result)
    return result


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    run()
