from typing import Iterable, List, Optional

import requests

REQUEST_TIMEOUT_S = 30
DEFAULT_BATCH_SIZE = 25


class SubmissionClient:
    """POSTs discovered/enriched churches to the server's scraper ingestion
    endpoint in batches.

    Contract (see docs/SCRAPER.md "Scrape-to-verification-queue flow"):
      POST {base_url}/api/scraper/church-submissions
      Header: X-Scraper-Api-Key: <api_key>
      Body:   {"submissions": [<church payload>, ...]}
      Response: 202 {"accepted": <int>}

    Each submission lands in the same `ChurchSubmission` verification queue a
    volunteer's edit goes through — the scraper never writes `Church` rows
    directly.
    """

    ENDPOINT = "/api/scraper/church-submissions"

    def __init__(
        self,
        base_url: str,
        api_key: str,
        session: Optional[requests.Session] = None,
        batch_size: int = DEFAULT_BATCH_SIZE,
    ):
        self._base_url = base_url.rstrip("/")
        self._api_key = api_key
        self._session = session or requests.Session()
        self._batch_size = batch_size

    def submit_all(self, submissions: Iterable[dict]) -> int:
        submissions = list(submissions)
        return sum(
            self._submit_batch(submissions[start:start + self._batch_size])
            for start in range(0, len(submissions), self._batch_size)
        )

    def _submit_batch(self, batch: List[dict]) -> int:
        response = self._session.post(
            f"{self._base_url}{self.ENDPOINT}",
            json={"submissions": batch},
            headers={"X-Scraper-Api-Key": self._api_key},
            timeout=REQUEST_TIMEOUT_S,
        )
        response.raise_for_status()
        return response.json().get("accepted", len(batch))
