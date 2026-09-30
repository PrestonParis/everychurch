import pytest
import responses
from requests.exceptions import ConnectionError

from scraper.existing_churches import _get_with_retry, _MAX_ATTEMPTS, fetch_all_existing_churches

URL = "https://api.example.com/api/churches"


def requests_session():
    import requests

    return requests.Session()


@responses.activate
def test_get_with_retry_succeeds_on_the_first_try():
    responses.add(responses.GET, URL, json={"ok": True})

    response = _get_with_retry(requests_session(), URL, {}, timeout=30)

    assert response.json() == {"ok": True}
    assert len(responses.calls) == 1


@responses.activate
def test_get_with_retry_recovers_from_a_transient_connection_reset(monkeypatch):
    # Confirmed live during the production bulk import: a load balancer resets a
    # long-lived reused connection partway through paging tens of thousands of rows —
    # not a real failure of that one request, so retrying it (not the whole run) is
    # what actually lets a full import finish.
    import scraper.existing_churches as m

    monkeypatch.setattr(m.time, "sleep", lambda _: None)
    responses.add(responses.GET, URL, body=ConnectionError("connection reset"))
    responses.add(responses.GET, URL, json={"ok": True})

    response = _get_with_retry(requests_session(), URL, {}, timeout=30)

    assert response.json() == {"ok": True}
    assert len(responses.calls) == 2


@responses.activate
def test_get_with_retry_raises_the_last_error_after_exhausting_attempts(monkeypatch):
    import scraper.existing_churches as m

    monkeypatch.setattr(m.time, "sleep", lambda _: None)
    for _ in range(_MAX_ATTEMPTS):
        responses.add(responses.GET, URL, body=ConnectionError("connection reset"))

    with pytest.raises(ConnectionError):
        _get_with_retry(requests_session(), URL, {}, timeout=30)

    assert len(responses.calls) == _MAX_ATTEMPTS


@responses.activate
def test_fetch_all_existing_churches_still_works_through_a_transient_retry(monkeypatch):
    import scraper.existing_churches as m

    monkeypatch.setattr(m.time, "sleep", lambda _: None)
    responses.add(responses.GET, URL, body=ConnectionError("connection reset"))
    responses.add(responses.GET, URL, json={"content": [{"name": "A"}], "totalPages": 1})

    result = fetch_all_existing_churches("https://api.example.com", requests_session())

    assert result == [{"name": "A"}]
