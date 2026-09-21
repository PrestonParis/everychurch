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

NOT_AVAILABLE = "NOT AVAILABLE"
DEFAULT_BATCH_SIZE = 100
DEFAULT_BATCH_DELAY_S = 1.0
DEFAULT_PAGE_SIZE = 200
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
        "denomination": _clean(row.get("denom")),
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


def fetch_all_existing_churches(base_url: str, session: requests.Session, page_size: int = DEFAULT_PAGE_SIZE) -> List[dict]:
    """Pages through GET /api/churches to build the full dedup set. Prompt 2 made
    that endpoint paginated ({content, totalPages, ...}); this also tolerates the
    older bare-array shape so the script keeps working regardless of whether that
    lands before or after this one — Phase A and B run concurrently."""
    base_url = base_url.rstrip("/")
    all_churches: List[dict] = []
    page = 0

    while True:
        response = session.get(f"{base_url}/api/churches", params={"page": page, "size": page_size}, timeout=30)
        response.raise_for_status()
        body = response.json()

        if isinstance(body, list):
            return body  # pre-Prompt-2 unpaginated shape: one shot, nothing to page through

        content = body.get("content", [])
        all_churches.extend(content)

        total_pages = body.get("totalPages", 1)
        page += 1
        if page >= total_pages or not content:
            break

    return all_churches


def submit_batch(config: BulkImportConfig, session: requests.Session, batch: List[dict]) -> int:
    response = session.post(
        f"{config.server_base_url.rstrip('/')}{BULK_IMPORT_ENDPOINT}",
        json={"churches": batch},
        headers={BULK_IMPORT_HEADER: config.bulk_import_api_key},
        timeout=60,
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
        created += submit_batch(config, session, batch)
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
