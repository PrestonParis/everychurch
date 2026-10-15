"""Backfills blank denominations on existing churches from each church's own name
("BETHEL AME CHURCH" -> Methodist, "IGLESIA BAUTISTA EMANUEL" -> Baptist), using the
same ordered name patterns as the HIFLD import (hifld_import._DENOMINATION_NAME_PATTERNS).

Why a separate pass: churches imported before those patterns grew (Nazarene, Church of
God, Apostolic, AME/CME, Spanish names, ...) kept a blank denomination, and the import
never revisits a row it already created. No download, no API key: it only reads the
directory and sends fills.

Same server endpoint and guarantees as the other fills: POST /api/scraper/bulk-import/fill
(ChurchService.fillBlanks) sets a denomination only where it is blank and never touches a
claimed church. A name that matches nothing stays blank rather than becoming "Other".

    python -m scraper.denomination_fill --dry-run
    python -m scraper.denomination_fill
"""

import argparse
import logging
import time
from collections import Counter
from typing import Callable, Iterable, List, Optional, Tuple

import requests

from .bulk_import import DEFAULT_BATCH_DELAY_S, DEFAULT_BATCH_SIZE
from .config import BulkImportConfig
from .existing_churches import fetch_all_existing_churches
from .hifld_import import _denomination_from_name
from .osm_fill import _blank, log_samples, submit_fills

logger = logging.getLogger(__name__)

SOURCE_NAME = "name"


def build_fills(existing: Iterable[dict]) -> Tuple[List[dict], dict]:
    """One {id, denomination} fill per unclaimed church whose denomination is blank and
    whose name names one. Stats count the blanks and the labels found."""
    fills: List[dict] = []
    labels: Counter = Counter()
    blank = 0
    for church in existing:
        if church.get("claimed") or not _blank(church.get("denomination")):
            continue
        blank += 1
        denomination = _denomination_from_name(church.get("name"))
        if denomination:
            fills.append({"id": church["id"], "denomination": denomination})
            labels[denomination] += 1
    return fills, {"blankDenomination": blank, "churchesToFill": len(fills), "byLabel": dict(labels)}


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

    existing = fetch_all_existing_churches(config.server_base_url, session)
    fills, stats = build_fills(existing)
    logger.info("denomination fill plan: %s", stats)

    if dry_run:
        log_samples(fills, {c.get("id"): c for c in existing})
        return {**stats, "updated": 0}

    updated = 0
    for start in range(0, len(fills), batch_size):
        batch = fills[start:start + batch_size]
        updated += submit_fills(config, session, batch, source=SOURCE_NAME)
        logger.info("denomination fill progress: %d/%d sent, %d updated", start + len(batch), len(fills), updated)
        if start + batch_size < len(fills):
            sleep(batch_delay_s)

    result = {**stats, "updated": updated}
    logger.info("denomination fill run: %s", result)
    return result


def main(argv: Optional[List[str]] = None) -> dict:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--dry-run", action="store_true", help="match and report, send nothing")
    args = parser.parse_args(argv)
    return run(dry_run=args.dry_run)


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    main()
