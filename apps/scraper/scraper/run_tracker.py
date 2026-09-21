from typing import Iterable, Optional

import requests

REQUEST_TIMEOUT_S = 30


class ScraperRunClient:
    """Talks to the server's scraper run tracking endpoints (docs/PROMPTS.md
    Prompt 5, docs/SCRAPER.md): starts/completes a ScraperRun row around one
    pipeline.run() invocation, and picks the next region to run when none was
    given. Same X-Scraper-Api-Key service-to-service auth as SubmissionClient.
    """

    def __init__(self, base_url: str, api_key: str, session: Optional[requests.Session] = None):
        self._base_url = base_url.rstrip("/")
        self._api_key = api_key
        self._session = session or requests.Session()

    def start(self, region: Optional[str]) -> int:
        response = self._session.post(
            f"{self._base_url}/api/scraper/runs",
            json={"region": region},
            headers={"X-Scraper-Api-Key": self._api_key},
            timeout=REQUEST_TIMEOUT_S,
        )
        response.raise_for_status()
        return response.json()["id"]

    def complete(
        self,
        run_id: int,
        status: str,
        discovered: Optional[int],
        new_count: Optional[int],
        accepted: Optional[int],
    ) -> None:
        response = self._session.patch(
            f"{self._base_url}/api/scraper/runs/{run_id}",
            json={"status": status, "discovered": discovered, "newCount": new_count, "accepted": accepted},
            headers={"X-Scraper-Api-Key": self._api_key},
            timeout=REQUEST_TIMEOUT_S,
        )
        response.raise_for_status()

    def next_region(self, candidates: Iterable[str]) -> Optional[str]:
        response = self._session.get(
            f"{self._base_url}/api/scraper/runs/next-region",
            params={"candidates": ",".join(candidates)},
            headers={"X-Scraper-Api-Key": self._api_key},
            timeout=REQUEST_TIMEOUT_S,
        )
        response.raise_for_status()
        return response.json().get("region")
