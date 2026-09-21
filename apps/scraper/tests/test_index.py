from unittest.mock import MagicMock, patch

from scraper import index
from scraper.config import ScraperConfig


def make_config():
    return ScraperConfig(
        places_api_key="places-key",
        scraper_api_key="scraper-key",
        server_base_url="https://api.example.com",
    )


def test_lambda_handler_uses_the_regions_event_key_without_asking_the_server():
    with patch("scraper.pipeline.run", return_value={"discovered": 1, "new": 1, "accepted": 1}) as run, patch(
        "scraper.pipeline.ScraperRunClient"
    ) as RunClientCls:
        result = index.lambda_handler(event={"region": "RI"}, context=None)

    RunClientCls.assert_not_called()
    run.assert_called_once_with(region="RI")
    assert result == {"discovered": 1, "new": 1, "accepted": 1}


def test_lambda_handler_asks_the_server_for_the_next_region_when_none_given():
    with patch("scraper.pipeline.run", return_value={"discovered": 0, "new": 0, "accepted": 0}) as run, patch(
        "scraper.pipeline.ScraperConfig.from_env", return_value=make_config()
    ), patch("scraper.pipeline.ScraperRunClient") as RunClientCls:
        run_client = RunClientCls.return_value
        run_client.next_region.return_value = "WY"

        result = index.lambda_handler(event={}, context=None)

    run_client.next_region.assert_called_once()
    run.assert_called_once_with(region="WY")
    assert result == {"discovered": 0, "new": 0, "accepted": 0}
