import pytest

from scraper.config import (
    DEFAULT_SEARCH_RADIUS_M,
    DEFAULT_SERVER_BASE_URL,
    BulkImportConfig,
    ScraperConfig,
)


def test_from_env_reads_required_and_optional_values(monkeypatch):
    monkeypatch.setenv("GOOGLE_PLACES_API_KEY", "places-key")
    monkeypatch.setenv("SCRAPER_API_KEY", "scraper-key")
    monkeypatch.setenv("SERVER_BASE_URL", "https://api.example.com")
    monkeypatch.setenv("SCRAPER_SEARCH_RADIUS_M", "10000")

    config = ScraperConfig.from_env()

    assert config.places_api_key == "places-key"
    assert config.scraper_api_key == "scraper-key"
    assert config.server_base_url == "https://api.example.com"
    assert config.search_radius_m == 10000


def test_from_env_uses_defaults_when_optional_values_missing(monkeypatch):
    monkeypatch.setenv("GOOGLE_PLACES_API_KEY", "places-key")
    monkeypatch.setenv("SCRAPER_API_KEY", "scraper-key")
    monkeypatch.delenv("SERVER_BASE_URL", raising=False)
    monkeypatch.delenv("SCRAPER_SEARCH_RADIUS_M", raising=False)

    config = ScraperConfig.from_env()

    assert config.server_base_url == DEFAULT_SERVER_BASE_URL
    assert config.search_radius_m == DEFAULT_SEARCH_RADIUS_M


@pytest.mark.parametrize("missing", ["GOOGLE_PLACES_API_KEY", "SCRAPER_API_KEY"])
def test_from_env_raises_when_required_value_missing(monkeypatch, missing):
    monkeypatch.setenv("GOOGLE_PLACES_API_KEY", "places-key")
    monkeypatch.setenv("SCRAPER_API_KEY", "scraper-key")
    monkeypatch.delenv(missing, raising=False)

    with pytest.raises(RuntimeError, match=missing):
        ScraperConfig.from_env()


def test_bulk_import_config_from_env_reads_required_and_optional_values(monkeypatch):
    monkeypatch.setenv("BULK_IMPORT_API_KEY", "bulk-key")
    monkeypatch.setenv("SERVER_BASE_URL", "https://api.example.com")

    config = BulkImportConfig.from_env()

    assert config.bulk_import_api_key == "bulk-key"
    assert config.server_base_url == "https://api.example.com"


def test_bulk_import_config_from_env_uses_default_server_base_url(monkeypatch):
    monkeypatch.setenv("BULK_IMPORT_API_KEY", "bulk-key")
    monkeypatch.delenv("SERVER_BASE_URL", raising=False)

    config = BulkImportConfig.from_env()

    assert config.server_base_url == DEFAULT_SERVER_BASE_URL


def test_bulk_import_config_from_env_raises_when_key_missing(monkeypatch):
    monkeypatch.delenv("BULK_IMPORT_API_KEY", raising=False)

    with pytest.raises(RuntimeError, match="BULK_IMPORT_API_KEY"):
        BulkImportConfig.from_env()
