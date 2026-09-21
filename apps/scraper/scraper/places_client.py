import time
from typing import Callable, Iterator, Optional

import requests

NEARBY_SEARCH_URL = "https://maps.googleapis.com/maps/api/place/nearbysearch/json"
DETAILS_URL = "https://maps.googleapis.com/maps/api/place/details/json"
DETAILS_FIELDS = "name,formatted_phone_number,website,formatted_address"

# Google returns this immediately if a next_page_token is used before it has
# "warmed up" — retrying a couple of seconds later succeeds.
TOKEN_NOT_READY_STATUS = "INVALID_REQUEST"
RETRYABLE_STATUSES = {"OVER_QUERY_LIMIT", "UNKNOWN_ERROR", TOKEN_NOT_READY_STATUS}

MAX_RETRIES = 5
RETRY_BACKOFF_S = 2.0
NEXT_PAGE_DELAY_S = 2.0
REQUEST_TIMEOUT_S = 15


class PlacesApiError(RuntimeError):
    def __init__(self, status: str, message: Optional[str]):
        super().__init__(f"Places API error: {status} ({message})")
        self.status = status


class PlacesClient:
    """Thin wrapper around the Google Places API (Nearby Search + Details).

    ``session`` and ``sleep`` are injectable so tests never hit the network or
    wait on real timers.
    """

    def __init__(
        self,
        api_key: str,
        session: Optional[requests.Session] = None,
        sleep: Callable[[float], None] = time.sleep,
    ):
        self._api_key = api_key
        self._session = session or requests.Session()
        self._sleep = sleep

    def search_nearby(self, lat: float, lon: float, radius_m: int, keyword: str = "church") -> Iterator[dict]:
        """Yields every raw place result for one tile, following pagination."""
        base_params = {
            "location": f"{lat},{lon}",
            "radius": radius_m,
            "keyword": keyword,
            "key": self._api_key,
        }
        page_token = None

        while True:
            params = {"pagetoken": page_token, "key": self._api_key} if page_token else base_params
            data = self._get_with_retries(NEARBY_SEARCH_URL, params)
            status = data.get("status")

            if status == "ZERO_RESULTS":
                return
            if status != "OK":
                raise PlacesApiError(status, data.get("error_message"))

            for result in data.get("results", []):
                yield result

            page_token = data.get("next_page_token")
            if not page_token:
                return

            self._sleep(NEXT_PAGE_DELAY_S)

    def get_details(self, place_id: str) -> dict:
        """Fetches phone/website/formatted address for a single place — not
        returned by Nearby Search, needed before enrichment can scrape a site.
        """
        params = {"place_id": place_id, "fields": DETAILS_FIELDS, "key": self._api_key}
        data = self._get_with_retries(DETAILS_URL, params)
        status = data.get("status")

        if status != "OK":
            raise PlacesApiError(status, data.get("error_message"))

        return data.get("result", {})

    def _get_with_retries(self, url: str, params: dict) -> dict:
        last_status = "UNKNOWN_ERROR"

        for attempt in range(MAX_RETRIES):
            response = self._session.get(url, params=params, timeout=REQUEST_TIMEOUT_S)
            response.raise_for_status()
            data = response.json()
            status = data.get("status")

            if status not in RETRYABLE_STATUSES:
                return data

            last_status = status
            self._sleep(RETRY_BACKOFF_S * (attempt + 1))

        raise PlacesApiError(last_status, "exceeded retry budget")
