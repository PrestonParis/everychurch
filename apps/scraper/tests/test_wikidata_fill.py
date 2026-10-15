from unittest.mock import MagicMock, patch
from urllib.parse import parse_qs

import pytest
import requests
import responses

from scraper.config import BulkImportConfig
from scraper.wikidata_fill import (
    COMMONS_API_URL,
    DETAILS_BATCH_SIZE,
    MAX_RETRIES,
    SPARQL_URL,
    USER_AGENT,
    _point,
    build_fills,
    fetch_church_ids,
    fetch_details,
    fetch_free_images,
    main,
    run,
    wikidata_fields,
)

ENTITY = "http://www.wikidata.org/entity/"
FILE_PATH = "http://commons.wikimedia.org/wiki/Special:FilePath/"


def make_config():
    return BulkImportConfig(bulk_import_api_key="bulk-secret", server_base_url="https://api.example.com")


def bindings(*rows):
    return {"results": {"bindings": [{k: {"value": v} for k, v in row.items()} for row in rows]}}


def known(id, name, lat=41.82, lon=-71.41, **fields):
    return {"id": id, "name": name, "latitude": lat, "longitude": lon, "state": "RI", **fields}


no_sleep = MagicMock()


# --- http / fetch -----------------------------------------------------------------

@responses.activate
def test_fetch_church_ids_parses_coordinates_and_keeps_the_first_per_item():
    responses.add(responses.POST, SPARQL_URL, json=bindings(
        {"item": f"{ENTITY}Q1", "coord": "Point(-71.41 41.82)"},
        {"item": f"{ENTITY}Q1", "coord": "Point(-70 40)"},
        {"item": f"{ENTITY}Q2", "coord": "not a point"},
    ))

    assert fetch_church_ids(requests.Session(), no_sleep) == {"Q1": {"lat": 41.82, "lon": -71.41}}
    request = responses.calls[0].request
    assert request.headers["User-Agent"] == USER_AGENT
    assert 'hint:optimizer "None"' in parse_qs(request.body)["query"][0]


def test_point_rejects_garbage():
    assert _point(None) is None
    assert _point("Point(-71.5 41.25)") == (41.25, -71.5)


@responses.activate
def test_requests_retry_past_timeouts_and_truncated_bodies():
    responses.add(responses.POST, SPARQL_URL, status=504)
    responses.add(responses.POST, SPARQL_URL, body='{"results": {"bind')
    responses.add(responses.POST, SPARQL_URL, json=bindings())

    assert fetch_church_ids(requests.Session(), MagicMock()) == {}
    assert len(responses.calls) == 3


@responses.activate
def test_requests_give_up_after_max_retries():
    for _ in range(MAX_RETRIES):
        responses.add(responses.POST, SPARQL_URL, status=500)

    with pytest.raises(RuntimeError, match="failed after"):
        fetch_church_ids(requests.Session(), MagicMock())


@responses.activate
def test_fetch_details_batches_ids_and_collects_every_value():
    responses.add(responses.POST, SPARQL_URL, json=bindings(
        {"item": f"{ENTITY}Q1", "label": "Grace Church", "religionLabel": "Christianity"},
        {"item": f"{ENTITY}Q1", "label": "Grace Church", "religionLabel": "Baptists"},
    ))
    responses.add(responses.POST, SPARQL_URL, json=bindings())
    sleep = MagicMock()
    qids = [f"Q{i}" for i in range(1, DETAILS_BATCH_SIZE + 2)]

    details = fetch_details(requests.Session(), qids, sleep)

    assert details == {"Q1": {"label": {"Grace Church"}, "religionLabel": {"Christianity", "Baptists"}}}
    assert len(responses.calls) == 2
    assert "wd:Q1 " in parse_qs(responses.calls[0].request.body)["query"][0]
    sleep.assert_called_once()


@responses.activate
def test_fetch_free_images_keeps_only_attribution_free_files():
    responses.add(responses.GET, COMMONS_API_URL, json={"query": {"pages": [
        {"title": "File:Free.jpg", "imageinfo": [{"extmetadata": {"AttributionRequired": {"value": "false"}}}]},
        {"title": "File:BySa.jpg", "imageinfo": [{"extmetadata": {"AttributionRequired": {"value": "true"}}}]},
        {"title": "File:NoMeta.jpg", "imageinfo": [{"extmetadata": []}]},
        {"title": "File:NoField.jpg", "imageinfo": [{"extmetadata": {"AttributionRequired": []}}]},
        {"title": "File:Missing.jpg", "missing": True},
    ]}})
    responses.add(responses.GET, COMMONS_API_URL, json={})
    sleep = MagicMock()
    files = ["Free.jpg", "BySa.jpg", "Missing.jpg"] + [f"Other{i}.jpg" for i in range(50)]

    assert fetch_free_images(requests.Session(), files + ["Free.jpg"], sleep) == {"Free.jpg"}
    assert len(responses.calls) == 2
    sleep.assert_called_once()


# --- map --------------------------------------------------------------------------

def test_wikidata_fields_maps_every_property():
    item = {
        "website": {"http://grace.example.org/"},
        "phone": {"+1-401-555-0100"},
        "email": {"mailto:office@grace.example.org"},
        "facebook": {"GraceRI"},
        "instagram": {"graceri"},
        "youtube": {"UC123"},
        "religionLabel": {"Christianity", "Methodism"},
        "image": {f"{FILE_PATH}Grace%20Church.jpg"},
    }

    assert wikidata_fields(item, {"Grace Church.jpg"}) == {
        "website": "http://grace.example.org/",
        "phone": "+1-401-555-0100",
        "email": "office@grace.example.org",
        "facebook": "https://www.facebook.com/GraceRI",
        "instagram": "https://www.instagram.com/graceri",
        "youtube": "https://www.youtube.com/channel/UC123",
        "denomination": "Methodist",
        "imageUrl": "https://commons.wikimedia.org/wiki/Special:FilePath/Grace%20Church.jpg?width=320",
    }


def test_wikidata_fields_drops_ambiguous_and_malformed_values():
    item = {
        "website": {"http://a.example.org", "http://b.example.org"},
        "phone": {"  "},
        "email": {"not-an-email"},
        "facebook": {"two words"},
        "religionLabel": {"Catholicism", "Lutheranism"},
        "image": {f"{FILE_PATH}NeedsCredit.jpg"},
    }

    assert wikidata_fields(item, set()) == {}


def test_wikidata_fields_edge_cases():
    assert wikidata_fields({"email": {"office@grace.example.org"}, "website": {"grace.example.org"}}, set()) == {
        "email": "office@grace.example.org"
    }
    assert wikidata_fields({"image": {"https://example.org/not-commons.jpg"}}, {"not-commons.jpg"}) == {}
    assert wikidata_fields({}, set()) == {}


# --- build_fills ------------------------------------------------------------------

def test_build_fills_matches_by_name_and_distance_only():
    churches = {
        "Q1": {"lat": 41.82, "lon": -71.41},
        "Q2": {"lat": 41.90, "lon": -71.41},
        "Q3": {"lat": 41.82, "lon": -71.41},
        "Q4": {"lat": 41.82, "lon": -71.41},
    }
    details = {
        "Q1": {"label": {"Grace Church"}, "website": {"http://grace.example.org"}, "phone": {"555"}},
        "Q2": {"label": {"Far Away Church"}, "website": {"http://far.example.org"}},
        "Q3": {"label": {"Blank Phone Church"}, "phone": {" "}},
        "Q4": {"website": {"http://nolabel.example.org"}},
    }
    existing = [known(1, "Grace Church", phone="401")]

    fills, stats = build_fills(churches, details, set(), existing)

    assert fills == [{"id": 1, "website": "http://grace.example.org"}]
    assert stats["wikidataChurches"] == 4
    assert stats["withFillableFields"] == 2
    assert stats["matched"] == 1
    assert stats["unmatched"] == 1
    assert stats["fill.website"] == 1
    assert stats["fill.imageUrl"] == 0


# --- run --------------------------------------------------------------------------

def _patched_run(fills, **kwargs):
    details = {"Q1": {"image": {f"{FILE_PATH}A.jpg"}}, "Q2": {}}
    with patch("scraper.wikidata_fill.fetch_church_ids", return_value={"Q1": {}, "Q2": {}}), \
            patch("scraper.wikidata_fill.fetch_details", return_value=details), \
            patch("scraper.wikidata_fill.fetch_free_images", return_value={"A.jpg"}) as free, \
            patch("scraper.wikidata_fill.fetch_all_existing_churches", return_value=[known(1, "A")]), \
            patch("scraper.wikidata_fill.build_fills", return_value=(fills, {"churchesToFill": len(fills)})), \
            patch("scraper.wikidata_fill.submit_fills", side_effect=lambda c, s, batch, source: len(batch)) as submit:
        sleep = MagicMock()
        result = run(make_config(), MagicMock(), sleep=sleep, **kwargs)
    return result, submit, sleep, free


def test_run_sends_fills_in_batches_tagged_wikidata():
    result, submit, sleep, free = _patched_run([{"id": i} for i in range(3)], batch_size=2)

    assert result == {"churchesToFill": 3, "updated": 3}
    assert submit.call_count == 2
    assert submit.call_args.kwargs == {"source": "wikidata"}
    assert sleep.call_count == 1
    assert free.call_args.args[1] == ["A.jpg"]


def test_run_dry_run_sends_nothing():
    result, submit, _, _ = _patched_run([{"id": 1, "phone": "1"}], dry_run=True)

    assert result == {"churchesToFill": 1, "updated": 0}
    submit.assert_not_called()


def test_main_parses_arguments():
    with patch("scraper.wikidata_fill.run", return_value={}) as run_mock:
        main(["--dry-run"])
        main([])

    assert run_mock.call_args_list[0].kwargs == {"dry_run": True}
    assert run_mock.call_args_list[1].kwargs == {"dry_run": False}
