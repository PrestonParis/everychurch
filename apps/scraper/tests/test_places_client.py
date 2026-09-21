import pytest
import responses

from scraper.places_client import (
    DETAILS_URL,
    NEARBY_SEARCH_URL,
    PlacesApiError,
    PlacesClient,
)


@pytest.fixture
def sleeps():
    calls = []
    return calls, calls.append


@responses.activate
def test_search_nearby_yields_results_and_follows_pagination(sleeps):
    calls, sleep = sleeps
    responses.add(
        responses.GET,
        NEARBY_SEARCH_URL,
        json={"status": "OK", "results": [{"place_id": "a"}], "next_page_token": "tok1"},
    )
    responses.add(
        responses.GET,
        NEARBY_SEARCH_URL,
        json={"status": "OK", "results": [{"place_id": "b"}]},
    )

    client = PlacesClient(api_key="key", sleep=sleep)
    results = list(client.search_nearby(lat=1.0, lon=2.0, radius_m=1000))

    assert [r["place_id"] for r in results] == ["a", "b"]
    assert calls == [2.0]  # one delay before following next_page_token


@responses.activate
def test_search_nearby_stops_on_zero_results(sleeps):
    _, sleep = sleeps
    responses.add(responses.GET, NEARBY_SEARCH_URL, json={"status": "ZERO_RESULTS"})

    client = PlacesClient(api_key="key", sleep=sleep)
    results = list(client.search_nearby(lat=1.0, lon=2.0, radius_m=1000))

    assert results == []


@responses.activate
def test_search_nearby_raises_on_unrecoverable_status(sleeps):
    _, sleep = sleeps
    responses.add(
        responses.GET,
        NEARBY_SEARCH_URL,
        json={"status": "REQUEST_DENIED", "error_message": "bad key"},
    )

    client = PlacesClient(api_key="key", sleep=sleep)
    with pytest.raises(PlacesApiError) as exc_info:
        list(client.search_nearby(lat=1.0, lon=2.0, radius_m=1000))

    assert exc_info.value.status == "REQUEST_DENIED"


@responses.activate
def test_search_nearby_retries_on_over_query_limit_then_succeeds(sleeps):
    calls, sleep = sleeps
    responses.add(responses.GET, NEARBY_SEARCH_URL, json={"status": "OVER_QUERY_LIMIT"})
    responses.add(
        responses.GET,
        NEARBY_SEARCH_URL,
        json={"status": "OK", "results": [{"place_id": "a"}]},
    )

    client = PlacesClient(api_key="key", sleep=sleep)
    results = list(client.search_nearby(lat=1.0, lon=2.0, radius_m=1000))

    assert [r["place_id"] for r in results] == ["a"]
    assert calls == [2.0]  # one backoff sleep before the retry succeeded


@responses.activate
def test_search_nearby_raises_after_exhausting_retries(sleeps):
    _, sleep = sleeps
    for _ in range(5):
        responses.add(responses.GET, NEARBY_SEARCH_URL, json={"status": "OVER_QUERY_LIMIT"})

    client = PlacesClient(api_key="key", sleep=sleep)
    with pytest.raises(PlacesApiError) as exc_info:
        list(client.search_nearby(lat=1.0, lon=2.0, radius_m=1000))

    assert exc_info.value.status == "OVER_QUERY_LIMIT"


@responses.activate
def test_get_details_returns_result(sleeps):
    _, sleep = sleeps
    responses.add(
        responses.GET,
        DETAILS_URL,
        json={"status": "OK", "result": {"website": "https://example.org"}},
    )

    client = PlacesClient(api_key="key", sleep=sleep)
    details = client.get_details("place-1")

    assert details == {"website": "https://example.org"}


@responses.activate
def test_get_details_raises_on_not_found(sleeps):
    _, sleep = sleeps
    responses.add(responses.GET, DETAILS_URL, json={"status": "NOT_FOUND"})

    client = PlacesClient(api_key="key", sleep=sleep)
    with pytest.raises(PlacesApiError) as exc_info:
        client.get_details("missing")

    assert exc_info.value.status == "NOT_FOUND"
