"""Shared by pipeline.py (live discovery) and bulk_import.py (one-time Kaggle
seed) — both need to page through the full church directory to build a dedup
set, and both used to duplicate this logic until a review found pipeline.py's
own copy hadn't been updated for Prompt 2's paginated GET /api/churches
response shape: it called `response.json()` directly and handed the resulting
`{content, totalPages, ...}` dict straight to dedup.filter_new, which iterates
it expecting a list of church dicts — iterating a dict iterates its *keys*
(strings), so the first `.get()` call inside raised
`AttributeError: 'str' object has no attribute 'get'` the moment a live run
ever reached that code path. Kept in its own module (not merged into
bulk_import.py, which pipeline.py already deliberately avoids importing) so
the live scraper's Lambda deployment doesn't pick up bulk_import's heavier
kagglehub/pandas dependencies just to get this one function.
"""

import logging
import time
from typing import List, Optional

import requests

logger = logging.getLogger(__name__)

DEFAULT_PAGE_SIZE = 200
_MAX_ATTEMPTS = 5
_BACKOFF_BASE_S = 2.0


def _get_with_retry(session: requests.Session, url: str, params: dict, timeout: int) -> requests.Response:
    """Confirmed live during the production bulk import: paging through tens of
    thousands of rows on one reused `requests.Session` connection occasionally
    hits a `ConnectionResetError` partway through (a load balancer resetting a
    long-lived reused connection, not a real failure of any single request) —
    and without this, that reset killed the *entire* run, forcing a full restart
    that re-fetches every already-seen page from scratch. Retrying just the one
    failed request is both cheaper and far more likely to actually finish."""
    last_error: Exception | None = None
    for attempt in range(1, _MAX_ATTEMPTS + 1):
        try:
            response = session.get(url, params=params, timeout=timeout)
            response.raise_for_status()
            return response
        except (requests.exceptions.ConnectionError, requests.exceptions.Timeout) as e:
            last_error = e
            if attempt < _MAX_ATTEMPTS:
                delay = _BACKOFF_BASE_S * (2 ** (attempt - 1))
                logger.warning("request to %s failed (attempt %d/%d): %s — retrying in %.0fs", url, attempt, _MAX_ATTEMPTS, e, delay)
                time.sleep(delay)
    raise last_error  # type: ignore[misc]


def fetch_all_existing_churches(
    base_url: str, session: requests.Session, page_size: int = DEFAULT_PAGE_SIZE, state: Optional[str] = None
) -> List[dict]:
    """Pages through GET /api/churches to build the full dedup set. Prompt 2 made
    that endpoint paginated ({content, totalPages, ...}); this also tolerates the
    older bare-array shape so callers keep working regardless of whether that
    lands before or after this one — Phase A and B run concurrently.

    `state` ("RI") fetches just that state's churches: 3 requests instead of the
    ~1,000 the whole directory takes, for a script that only needs one state."""
    base_url = base_url.rstrip("/")
    filters = {"state": state} if state else {}
    all_churches: List[dict] = []
    page = 0

    while True:
        # 120s, not 30 — GET /api/churches with no query defaults to a random
        # shuffle-sort (md5(seed || id), for browsing variety) rather than an
        # indexed order, which gets meaningfully slower as the table grows toward
        # tens of thousands of rows. Confirmed live: 30s was too tight once this
        # crossed ~30k existing churches during the initial bulk import.
        response = _get_with_retry(
            session, f"{base_url}/api/churches", params={"page": page, "size": page_size, **filters}, timeout=120
        )
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
