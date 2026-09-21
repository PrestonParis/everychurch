import json

import responses

from scraper.submission_client import SubmissionClient


@responses.activate
def test_submit_all_sends_a_single_batch_with_auth_header():
    responses.add(
        responses.POST,
        "https://api.example.com/api/scraper/church-submissions",
        json={"accepted": 2},
        status=202,
    )

    client = SubmissionClient("https://api.example.com/", api_key="secret")
    accepted = client.submit_all([{"name": "A"}, {"name": "B"}])

    assert accepted == 2
    request = responses.calls[0].request
    assert request.headers["X-Scraper-Api-Key"] == "secret"
    assert json.loads(request.body) == {"submissions": [{"name": "A"}, {"name": "B"}]}


@responses.activate
def test_submit_all_splits_into_multiple_batches():
    responses.add(
        responses.POST,
        "https://api.example.com/api/scraper/church-submissions",
        json={"accepted": 2},
        status=202,
    )
    responses.add(
        responses.POST,
        "https://api.example.com/api/scraper/church-submissions",
        json={"accepted": 1},
        status=202,
    )

    client = SubmissionClient("https://api.example.com", api_key="secret", batch_size=2)
    accepted = client.submit_all([{"name": "A"}, {"name": "B"}, {"name": "C"}])

    assert accepted == 3
    assert len(responses.calls) == 2
    assert json.loads(responses.calls[0].request.body) == {
        "submissions": [{"name": "A"}, {"name": "B"}]
    }
    assert json.loads(responses.calls[1].request.body) == {"submissions": [{"name": "C"}]}


@responses.activate
def test_submit_all_falls_back_to_batch_length_when_response_omits_accepted():
    responses.add(
        responses.POST,
        "https://api.example.com/api/scraper/church-submissions",
        json={},
        status=202,
    )

    client = SubmissionClient("https://api.example.com", api_key="secret")
    accepted = client.submit_all([{"name": "A"}])

    assert accepted == 1


def test_submit_all_with_no_submissions_makes_no_requests():
    client = SubmissionClient("https://api.example.com", api_key="secret")

    assert client.submit_all([]) == 0
