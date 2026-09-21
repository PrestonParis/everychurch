import os
from dataclasses import dataclass

DEFAULT_SEARCH_RADIUS_M = 25_000
DEFAULT_SERVER_BASE_URL = "http://localhost:8080"


@dataclass(frozen=True)
class ScraperConfig:
    places_api_key: str
    scraper_api_key: str
    server_base_url: str = DEFAULT_SERVER_BASE_URL
    search_radius_m: int = DEFAULT_SEARCH_RADIUS_M

    @classmethod
    def from_env(cls) -> "ScraperConfig":
        return cls(
            places_api_key=_require_env("GOOGLE_PLACES_API_KEY"),
            scraper_api_key=_require_env("SCRAPER_API_KEY"),
            server_base_url=os.environ.get("SERVER_BASE_URL", DEFAULT_SERVER_BASE_URL),
            search_radius_m=int(os.environ.get("SCRAPER_SEARCH_RADIUS_M", DEFAULT_SEARCH_RADIUS_M)),
        )


@dataclass(frozen=True)
class BulkImportConfig:
    """Separate from ScraperConfig on purpose (Prompt 7): bulk_import.py doesn't use
    GOOGLE_PLACES_API_KEY or SCRAPER_API_KEY at all, and its own key
    (BULK_IMPORT_API_KEY) is intentionally a different secret — reusing ScraperConfig
    would force requiring env vars this script has no use for.
    """

    bulk_import_api_key: str
    server_base_url: str = DEFAULT_SERVER_BASE_URL

    @classmethod
    def from_env(cls) -> "BulkImportConfig":
        return cls(
            bulk_import_api_key=_require_env("BULK_IMPORT_API_KEY"),
            server_base_url=os.environ.get("SERVER_BASE_URL", DEFAULT_SERVER_BASE_URL),
        )


def _require_env(name: str) -> str:
    value = os.environ.get(name)
    if not value:
        raise RuntimeError(f"{name} environment variable is required")
    return value
