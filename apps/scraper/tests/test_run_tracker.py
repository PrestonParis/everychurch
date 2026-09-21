import json

import responses

from scraper.run_tracker import ScraperRunClient


@responses.activate
def test_start_posts_region_and_returns_the_new_run_id():
    responses.add(
        responses.POST,
        "https://api.example.com/api/scraper/runs",
        json={"id": 5, "region": "RI", "status": "RUNNING"},
        status=201,
    )

    client = ScraperRunClient("https://api.example.com", api_key="secret")
    run_id = client.start("RI")

    assert run_id == 5
    request = responses.calls[0].request
    assert request.headers["X-Scraper-Api-Key"] == "secret"
    assert json.loads(request.body) == {"region": "RI"}


@responses.activate
def test_start_with_no_region_posts_null():
    responses.add(
        responses.POST,
        "https://api.example.com/api/scraper/runs",
        json={"id": 6, "region": None, "status": "RUNNING"},
        status=201,
    )

    client = ScraperRunClient("https://api.example.com", api_key="secret")
    client.start(None)

    assert json.loads(responses.calls[0].request.body) == {"region": None}


@responses.activate
def test_complete_patches_status_and_counts():
    responses.add(
        responses.PATCH,
        "https://api.example.com/api/scraper/runs/5",
        json={"id": 5, "status": "COMPLETED"},
        status=200,
    )

    client = ScraperRunClient("https://api.example.com", api_key="secret")
    client.complete(5, "COMPLETED", discovered=10, new_count=3, accepted=3)

    request = responses.calls[0].request
    assert request.headers["X-Scraper-Api-Key"] == "secret"
    assert json.loads(request.body) == {
        "status": "COMPLETED",
        "discovered": 10,
        "newCount": 3,
        "accepted": 3,
    }


@responses.activate
def test_next_region_sends_comma_joined_candidates_and_returns_the_pick():
    responses.add(
        responses.GET,
        "https://api.example.com/api/scraper/runs/next-region",
        json={"region": "WY"},
        status=200,
    )

    client = ScraperRunClient("https://api.example.com", api_key="secret")
    region = client.next_region(["RI", "WY", "AK"])

    assert region == "WY"
    assert responses.calls[0].request.params["candidates"] == "RI,WY,AK"
