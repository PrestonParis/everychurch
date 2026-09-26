from unittest.mock import MagicMock, patch

import requests
import responses

from scraper.config import ScraperConfig
from scraper.pipeline import _build_submission, _to_church_dto_shape, run


def make_config():
    return ScraperConfig(
        places_api_key="places-key",
        scraper_api_key="scraper-key",
        server_base_url="https://api.example.com",
        search_radius_m=1000,
    )


@responses.activate
def test_run_dedupes_correctly_against_the_real_paginated_churches_response():
    """Regression test for a bug found in review: pipeline.py used to fetch
    GET /api/churches with `response.json()` and hand the result straight to
    dedup.filter_new, which expects a list of church dicts. Since Prompt 2 that
    endpoint returns a paginated `{content, totalPages, ...}` object instead of
    a bare array — iterating that dict iterates its *keys* (strings), so the
    first `.get()` inside raised `AttributeError: 'str' object has no attribute
    'get'` on any real (non-empty) server the moment a live run reached this
    code path. Exercises the real HTTP shape end-to-end through run(), not a
    mocked-away fetch, so this would have caught it."""
    responses.add(
        responses.GET,
        "https://api.example.com/api/churches",
        json={"content": [{"name": "Existing Church", "zipCode": "60614"}], "totalPages": 1},
    )
    config = make_config()

    with patch("scraper.pipeline.PlacesClient"), patch(
        "scraper.pipeline.discover_churches",
        return_value=[
            {"place_id": "a", "name": "Existing Church", "zip_code": "60614"},  # already known -> filtered out
            {"place_id": "b", "name": "New Church", "zip_code": "60615"},
        ],
    ), patch(
        "scraper.pipeline._build_submission", return_value={"name": "New Church"}
    ) as build_submission, patch(
        "scraper.pipeline.SubmissionClient"
    ) as SubmissionClientCls, patch(
        "scraper.pipeline.ScraperRunClient"
    ) as RunClientCls:
        submission_client = SubmissionClientCls.return_value
        submission_client.submit_all.return_value = 1
        RunClientCls.return_value.start.return_value = 1

        result = run(config=config, session=requests.Session())

    build_submission.assert_called_once()  # only the genuinely new church reaches submission
    assert result == {"discovered": 2, "new": 1, "accepted": 1}


def test_build_submission_merges_details_and_enrichment_without_overwriting_with_falsy_values():
    places_client = MagicMock()
    places_client.get_details.return_value = {
        "formatted_phone_number": "555-1234",
        "website": "https://example.church",
        "formatted_address": "1 Main St",
    }
    session = MagicMock()

    with patch(
        "scraper.pipeline.enrich_from_website",
        return_value={"description": "Nice church", "name": None},
    ) as enrich:
        payload = _build_submission({"place_id": "abc", "name": "Grace"}, places_client, session)

    enrich.assert_called_once_with("https://example.church", session)
    assert payload["phone"] == "555-1234"
    assert payload["website"] == "https://example.church"
    assert payload["address"] == "1 Main St"
    assert payload["description"] == "Nice church"
    assert payload["name"] == "Grace"


def test_build_submission_skips_enrichment_without_a_website():
    places_client = MagicMock()
    places_client.get_details.return_value = {}
    session = MagicMock()

    with patch("scraper.pipeline.enrich_from_website") as enrich:
        payload = _build_submission({"place_id": "abc", "name": "Grace"}, places_client, session)

    enrich.assert_not_called()
    assert payload["phone"] is None
    assert payload["website"] is None


def test_run_orchestrates_discovery_dedup_enrichment_and_submission():
    config = make_config()

    with patch("scraper.pipeline.PlacesClient"), patch(
        "scraper.pipeline.discover_churches",
        return_value=[{"place_id": "a", "name": "A", "zip_code": "1"}],
    ), patch("scraper.pipeline.fetch_all_existing_churches", return_value=[]), patch(
        "scraper.pipeline.filter_new",
        return_value=[{"place_id": "a", "name": "A", "zip_code": "1"}],
    ), patch(
        "scraper.pipeline._build_submission", return_value={"name": "A"}
    ) as build_submission, patch(
        "scraper.pipeline.SubmissionClient"
    ) as SubmissionClientCls, patch(
        "scraper.pipeline.ScraperRunClient"
    ) as RunClientCls:
        submission_client = SubmissionClientCls.return_value
        submission_client.submit_all.return_value = 1
        run_client = RunClientCls.return_value
        run_client.start.return_value = 42

        result = run(config=config, session=MagicMock())

    build_submission.assert_called_once()
    submission_client.submit_all.assert_called_once_with([{"name": "A"}])
    run_client.start.assert_called_once_with(None)
    run_client.complete.assert_called_once_with(42, "COMPLETED", 1, 1, 1)
    assert result == {"discovered": 1, "new": 1, "accepted": 1}


def test_run_marks_the_run_failed_and_reraises_on_error():
    config = make_config()

    with patch("scraper.pipeline.PlacesClient"), patch(
        "scraper.pipeline.discover_churches", side_effect=RuntimeError("places API down")
    ), patch("scraper.pipeline.ScraperRunClient") as RunClientCls:
        run_client = RunClientCls.return_value
        run_client.start.return_value = 7

        try:
            run(config=config, session=MagicMock())
            assert False, "expected RuntimeError to propagate"
        except RuntimeError:
            pass

    run_client.complete.assert_called_once_with(7, "FAILED", None, None, None)


def test_run_scopes_tiling_to_the_regions_bounding_box_when_given():
    config = make_config()

    with patch("scraper.pipeline.PlacesClient"), patch(
        "scraper.pipeline.discover_churches", return_value=[]
    ) as discover_churches, patch(
        "scraper.pipeline.fetch_all_existing_churches", return_value=[]
    ), patch("scraper.pipeline.filter_new", return_value=[]), patch(
        "scraper.pipeline.SubmissionClient"
    ) as SubmissionClientCls, patch("scraper.pipeline.ScraperRunClient") as RunClientCls:
        SubmissionClientCls.return_value.submit_all.return_value = 0
        RunClientCls.return_value.start.return_value = 1

        run(config=config, session=MagicMock(), region="RI")

    _, kwargs = discover_churches.call_args
    tiles = list(kwargs["tiles"])
    assert len(tiles) > 0
    # every generated tile falls within Rhode Island's (generous) bounding box
    for tile in tiles:
        assert 41.1 <= tile.lat <= 42.1
        assert -71.9 <= tile.lon <= -71.0


def test_run_rejects_an_unknown_region():
    config = make_config()

    with patch("scraper.pipeline.PlacesClient"), patch("scraper.pipeline.ScraperRunClient") as RunClientCls:
        RunClientCls.return_value.start.return_value = 1

        try:
            run(config=config, session=MagicMock(), region="ZZ")
            assert False, "expected ValueError for an unknown region code"
        except ValueError:
            pass


def test_to_church_dto_shape_translates_snake_case_and_lat_lon_to_camel_case():
    payload = {
        "zip_code": "60614",
        "image_url": "https://example.church/logo.png",
        "lat": 41.9,
        "lon": -87.6,
        "name": "Grace",
        "address": "1 Main St",
        "city": "Chicago",
        "state": "IL",
        "phone": "555-1234",
        "website": "https://example.church",
        "description": "Nice church",
    }

    result = _to_church_dto_shape(payload)

    assert result == {
        "name": "Grace",
        "address": "1 Main St",
        "city": "Chicago",
        "state": "IL",
        "zipCode": "60614",
        "phone": "555-1234",
        "website": "https://example.church",
        "description": "Nice church",
        "imageUrl": "https://example.church/logo.png",
        "latitude": 41.9,
        "longitude": -87.6,
    }


def test_build_submission_output_uses_church_dto_field_names():
    places_client = MagicMock()
    places_client.get_details.return_value = {}
    session = MagicMock()

    payload = _build_submission(
        {"place_id": "abc", "name": "Grace", "lat": 41.9, "lon": -87.6},
        places_client,
        session,
    )

    assert payload["latitude"] == 41.9
    assert payload["longitude"] == -87.6
    assert "lat" not in payload
    assert "place_id" not in payload


def test_run_defaults_config_and_session_when_not_given():
    with patch("scraper.pipeline.ScraperConfig.from_env", return_value=make_config()), patch(
        "scraper.pipeline.PlacesClient"
    ), patch("scraper.pipeline.discover_churches", return_value=[]), patch(
        "scraper.pipeline.fetch_all_existing_churches", return_value=[]
    ), patch(
        "scraper.pipeline.filter_new", return_value=[]
    ), patch(
        "scraper.pipeline.SubmissionClient"
    ) as SubmissionClientCls, patch("scraper.pipeline.ScraperRunClient") as RunClientCls:
        submission_client = SubmissionClientCls.return_value
        submission_client.submit_all.return_value = 0
        RunClientCls.return_value.start.return_value = 1

        result = run()

    assert result == {"discovered": 0, "new": 0, "accepted": 0}
