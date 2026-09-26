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

from typing import List

import requests

DEFAULT_PAGE_SIZE = 200


def fetch_all_existing_churches(base_url: str, session: requests.Session, page_size: int = DEFAULT_PAGE_SIZE) -> List[dict]:
    """Pages through GET /api/churches to build the full dedup set. Prompt 2 made
    that endpoint paginated ({content, totalPages, ...}); this also tolerates the
    older bare-array shape so callers keep working regardless of whether that
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
