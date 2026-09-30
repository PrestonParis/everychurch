from unittest.mock import MagicMock, patch

import pytest
import responses
from requests.exceptions import ConnectionError

from scraper.bulk_import import BULK_IMPORT_ENDPOINT
from scraper.config import BulkImportConfig
from scraper.hifld_import import (
    HIFLD_QUERY_URL,
    _clean,
    _denomination_from_name,
    _zip_code,
    fetch_all_records,
    has_coordinates,
    is_church,
    run,
    to_church_dto_shape,
)


def make_config():
    return BulkImportConfig(bulk_import_api_key="bulk-secret", server_base_url="https://api.example.com")


def requests_session():
    import requests

    return requests.Session()


# --- _clean / _zip_code ----------------------------------------------------------

def test_clean_returns_none_for_none():
    assert _clean(None) is None


def test_clean_returns_none_for_blank_string():
    assert _clean("   ") is None


def test_clean_strips_a_real_value():
    assert _clean("  First Baptist Church  ") == "First Baptist Church"


def test_zip_code_returns_none_for_missing_zip():
    assert _zip_code(None) is None


def test_zip_code_leaves_a_plain_five_digit_zip_unchanged():
    assert _zip_code("04101") == "04101"


def test_zip_code_strips_the_plus_four_suffix():
    assert _zip_code("04101-1234") == "04101"


def test_zip_code_returns_none_for_non_numeric_junk():
    assert _zip_code("UNKNOWN") is None


# --- _denomination_from_name ------------------------------------------------------

def test_denomination_from_name_matches_known_keywords():
    assert _denomination_from_name("SAINT MARY CATHOLIC CHURCH") == "Catholic"
    assert _denomination_from_name("FIRST BAPTIST CHURCH") == "Baptist"
    assert _denomination_from_name("TRINITY LUTHERAN CHURCH") == "Lutheran"
    assert _denomination_from_name("HOLY TRINITY ORTHODOX CHURCH") == "Eastern Orthodox"
    assert _denomination_from_name("CHURCH OF CHRIST ON MAIN ST") == "Church of Christ"
    assert _denomination_from_name("SAINT JOHN EPISCOPAL CHURCH") == "Episcopal/Anglican"
    assert _denomination_from_name("GRACE ANGLICAN CHURCH") == "Episcopal/Anglican"
    assert _denomination_from_name("CHURCH OF JESUS CHRIST OF LATTER-DAY SAINTS") == "Latter-day Saint (LDS)"
    assert _denomination_from_name("MORMON TABERNACLE") == "Latter-day Saint (LDS)"
    assert _denomination_from_name("FIRST METHODIST CHURCH") == "Methodist"
    assert _denomination_from_name("FULL GOSPEL PENTECOSTAL CHURCH") == "Pentecostal / Assembly of God"
    assert _denomination_from_name("ASSEMBLY OF GOD TABERNACLE") == "Pentecostal / Assembly of God"
    assert _denomination_from_name("FIRST PRESBYTERIAN CHURCH") == "Presbyterian"
    assert _denomination_from_name("SEVENTH-DAY ADVENTIST CHURCH") == "Seventh-day Adventist"
    assert _denomination_from_name("GRACE NON-DENOMINATIONAL CHURCH") == "Non-denominational"
    assert _denomination_from_name("FAITH NONDENOMINATIONAL FELLOWSHIP") == "Non-denominational"
    assert _denomination_from_name("GRACE EVANGELICAL CHURCH") == "Evangelical"


def test_denomination_from_name_returns_none_when_nothing_matches():
    assert _denomination_from_name("COMMUNITY CHURCH") is None


def test_denomination_from_name_returns_none_for_blank_name():
    assert _denomination_from_name(None) is None
    assert _denomination_from_name("") is None


# --- is_church ---------------------------------------------------------------------

def test_is_church_true_for_a_subordinate_congregation():
    assert is_church({"NAME": "FIRST BAPTIST CHURCH", "AFFILIATIO": "9"}) is True


def test_is_church_true_for_an_independent_congregation():
    assert is_church({"NAME": "GRACE CHAPEL", "AFFILIATIO": "3"}) is True


def test_is_church_false_for_a_central_group_ruling_parent():
    # AFFILIATIO 8 = a diocese/denominational headquarters, not an individual church
    assert is_church({"NAME": "DIOCESE OF SOME CITY", "AFFILIATIO": "8"}) is False


def test_is_church_false_for_a_national_or_intermediate_organization():
    assert is_church({"NAME": "GENERAL COUNCIL OF ASSEMBLIES OF GOD", "AFFILIATIO": "1"}) is False


def test_is_church_does_not_exclude_on_a_missing_affiliation():
    # Real churches sometimes have no AFFILIATIO at all — absence isn't a fail signal.
    assert is_church({"NAME": "FIRST BAPTIST CHURCH", "AFFILIATIO": None}) is True


def test_is_church_false_for_a_council_of_churches_even_with_a_congregation_affiliation():
    assert is_church({"NAME": "MAINE COUNCIL OF CHURCHES", "AFFILIATIO": "3"}) is False


def test_is_church_false_for_a_school_even_with_a_congregation_affiliation():
    assert is_church({"NAME": "CHEVERUS HIGH SCHOOL", "AFFILIATIO": "9"}) is False


def test_is_church_false_for_another_faiths_place_of_worship():
    assert is_church({"NAME": "TEMPLE BETH ISRAEL SYNAGOGUE", "AFFILIATIO": "3"}) is False


def test_is_church_false_when_name_matches_no_include_keyword():
    assert is_church({"NAME": "BIBLE SOCIETY OF MAINE", "AFFILIATIO": "3"}) is False


def test_is_church_handles_a_numeric_affiliation_value():
    # The live service returns AFFILIATIO as a JSON number, not a string.
    assert is_church({"NAME": "FIRST BAPTIST CHURCH", "AFFILIATIO": 9}) is True


def test_is_church_false_for_a_parachurch_evangelism_ministry():
    # Found in a real spot check: "Child Evangelism Fellowship Inc" matched the plain
    # "fellowship" include keyword despite being a parachurch org, not a congregation.
    assert is_church({"NAME": "CHILD EVANGELISM FELLOWSHIP INC", "AFFILIATIO": "3"}) is False


def test_is_church_false_for_a_denominational_general_council():
    assert is_church({"NAME": "GENERAL COUNCIL OF ASSEMBLIES OF GOD FULL GOSPEL TABERNACLE", "AFFILIATIO": "3"}) is False


def test_is_church_false_for_a_regional_conference_body():
    # Found in a real spot check: a UCC regional governing body, not a congregation,
    # that still contains "church" in its name.
    assert is_church({"NAME": "MAINE CONFERENCE UNITED CHURCH OF CHRIST", "AFFILIATIO": "3"}) is False


# --- to_church_dto_shape ------------------------------------------------------------

def test_to_church_dto_shape_maps_the_real_service_fields():
    row = {
        "NAME": "First Baptist Church",
        "STREET": "1 Main St",
        "CITY": "Portland",
        "STATE": "ME",
        "ZIP": "04101-1234",
        "X": -70.25,
        "Y": 43.66,
        "AFFILIATIO": "9",
    }

    result = to_church_dto_shape(row)

    assert result == {
        "name": "First Baptist Church",
        "denomination": "Baptist",
        "address": "1 Main St",
        "city": "Portland",
        "state": "ME",
        "zipCode": "04101",
        "phone": None,
        "latitude": 43.66,
        "longitude": -70.25,
    }


def test_to_church_dto_shape_handles_missing_fields():
    result = to_church_dto_shape({"NAME": "Community Church"})

    assert result["denomination"] is None
    assert result["address"] is None
    assert result["zipCode"] is None
    assert result["latitude"] is None
    assert result["longitude"] is None


# --- fetch_all_records ---------------------------------------------------------------

@responses.activate
def test_fetch_all_records_stops_after_a_partial_final_page():
    responses.add(
        responses.GET,
        HIFLD_QUERY_URL,
        json={"features": [{"attributes": {"NAME": "A"}, "geometry": {"x": -71.0, "y": 42.3}}]},
    )

    result = fetch_all_records(requests_session())

    assert result == [{"NAME": "A", "X": -71.0, "Y": 42.3}]
    assert len(responses.calls) == 1


@responses.activate
def test_fetch_all_records_merges_geometry_and_tolerates_a_null_geometry():
    responses.add(
        responses.GET,
        HIFLD_QUERY_URL,
        json={
            "features": [
                {"attributes": {"NAME": "A"}, "geometry": {"x": -71.0, "y": 42.3}},
                {"attributes": {"NAME": "B"}, "geometry": None},
            ]
        },
    )

    result = fetch_all_records(requests_session())

    assert result == [{"NAME": "A", "X": -71.0, "Y": 42.3}, {"NAME": "B"}]


@responses.activate
def test_fetch_all_records_pages_through_full_pages(monkeypatch):
    import scraper.hifld_import as hifld_import

    monkeypatch.setattr(hifld_import, "HIFLD_PAGE_SIZE", 1)
    responses.add(
        responses.GET,
        HIFLD_QUERY_URL,
        json={"features": [{"attributes": {"NAME": "A"}, "geometry": {"x": -71.0, "y": 42.3}}]},
    )
    responses.add(
        responses.GET,
        HIFLD_QUERY_URL,
        json={"features": []},
    )

    result = hifld_import.fetch_all_records(requests_session())

    assert result == [{"NAME": "A", "X": -71.0, "Y": 42.3}]
    assert len(responses.calls) == 2
    assert responses.calls[1].request.params["resultOffset"] == "1"


@responses.activate
def test_fetch_all_records_retries_past_a_transient_connection_drop():
    # Confirmed against the live service: the very first request of a real run once
    # failed with exactly this — a plain RemoteDisconnected with no HTTP response at
    # all — and fetch_all_records previously had no retry, losing the whole run.
    sleeps = []
    responses.add(responses.GET, HIFLD_QUERY_URL, body=ConnectionError("boom"))
    responses.add(
        responses.GET,
        HIFLD_QUERY_URL,
        json={"features": [{"attributes": {"NAME": "A"}, "geometry": {"x": -71.0, "y": 42.3}}]},
    )

    result = fetch_all_records(requests_session(), sleep=sleeps.append)

    assert result == [{"NAME": "A", "X": -71.0, "Y": 42.3}]
    assert len(responses.calls) == 2
    assert sleeps == [2.0]  # HIFLD_RETRY_BACKOFF_S * (attempt 0 + 1)


@responses.activate
def test_fetch_all_records_raises_after_exhausting_retries():
    from scraper.hifld_import import HIFLD_MAX_RETRIES

    responses.add(responses.GET, HIFLD_QUERY_URL, body=ConnectionError("boom"))

    with pytest.raises(RuntimeError, match="HIFLD query failed after"):
        fetch_all_records(requests_session(), sleep=lambda _: None)

    assert len(responses.calls) == HIFLD_MAX_RETRIES


@responses.activate
def test_fetch_all_records_raises_on_an_error_body_despite_http_200():
    # ArcGIS returns HTTP 200 even when the query itself failed (e.g. a bad field
    # name) — the failure is only visible in the JSON body. Confirmed against the
    # live service while developing this: a since-fixed bad outFields value produced
    # exactly this "200 OK with an error payload" response.
    responses.add(
        responses.GET,
        HIFLD_QUERY_URL,
        json={"error": {"code": 400, "message": "Cannot perform query. Invalid query parameters."}},
    )

    with pytest.raises(RuntimeError, match="HIFLD query failed"):
        fetch_all_records(requests_session())


@pytest.mark.parametrize(
    "name",
    [
        "EMMANUEL ASSEMBLY OF GOD",
        "IGLESIA BETHESDA INC",
        "MINISTERIO APOSTOLICO JESUCRISTO ES EL SENOR INC",
        "IGREJA BATISTA BRASILEIRA",
        "PISCATAQUIS RIVER CHRISTIAN CENTER",
        "LITTLE HOUSE OF PRAYER INC",
    ],
)
def test_is_church_true_for_congregations_that_never_say_church(name):
    # Real names from the live service that the original include list missed.
    assert is_church({"NAME": name, "AFFILIATIO": "3"}) is True


# --- has_coordinates -----------------------------------------------------------------

def test_has_coordinates_true_when_both_present():
    assert has_coordinates({"X": -71.04, "Y": 42.35}) is True


def test_has_coordinates_false_when_either_is_missing():
    assert has_coordinates({"X": -71.04}) is False
    assert has_coordinates({"Y": 42.35}) is False
    assert has_coordinates({}) is False


def test_has_coordinates_true_for_a_zero_coordinate():
    # 0.0 is a real (if unlikely) value, not a missing one.
    assert has_coordinates({"X": 0.0, "Y": 0.0}) is True


# --- run -------------------------------------------------------------------------------

def test_run_orchestrates_fetch_filter_dedupe_and_ingest():
    config = make_config()
    raw = [
        {"NAME": "First Baptist Church", "STATE": "ME", "ZIP": "04101", "AFFILIATIO": "9", "X": -70.2, "Y": 43.6},
        {"NAME": "Maine Council of Churches", "STATE": "ME", "ZIP": "04102", "AFFILIATIO": "3", "X": -70.2, "Y": 43.6},
        {"NAME": "Grace Chapel", "STATE": "TX", "ZIP": "75001", "AFFILIATIO": "9", "X": -70.2, "Y": 43.6},
    ]

    with patch("scraper.hifld_import.fetch_all_records", return_value=raw), patch(
        "scraper.hifld_import.fetch_all_existing_churches",
        return_value=[{"name": "Grace Chapel", "zipCode": "75001"}],
    ), patch("scraper.hifld_import.submit_batch", return_value=1) as submit, patch(
        "scraper.hifld_import.time.sleep"
    ) as sleep:
        result = run(config=config, session=MagicMock(), batch_size=10)

    # Council of Churches filtered out by is_church; Grace Chapel filtered out by
    # existing-church dedup — only First Baptist Church should ever reach submit_batch.
    submit.assert_called_once()
    batch_arg = submit.call_args[0][2]
    assert [c["name"] for c in batch_arg] == ["First Baptist Church"]
    sleep.assert_not_called()
    assert result == {
        "totalRecords": 3,
        "churchRecords": 2,
        "withCoordinates": 2,
        "afterInternalDedup": 2,
        "new": 1,
        "created": 1,
    }


def test_run_skips_churches_without_coordinates_so_the_server_never_geocodes():
    raw = [
        {"NAME": "First Baptist Church", "ZIP": "04101", "AFFILIATIO": "9", "X": -70.2, "Y": 43.6},
        {"NAME": "Grace Chapel", "ZIP": "75001", "AFFILIATIO": "9"},
    ]

    with patch("scraper.hifld_import.fetch_all_records", return_value=raw), patch(
        "scraper.hifld_import.fetch_all_existing_churches", return_value=[]
    ), patch("scraper.hifld_import.submit_batch", return_value=1) as submit:
        result = run(config=make_config(), session=MagicMock(), batch_size=10)

    batch_arg = submit.call_args[0][2]
    assert [c["name"] for c in batch_arg] == ["First Baptist Church"]
    assert all(c["latitude"] is not None and c["longitude"] is not None for c in batch_arg)
    assert result["churchRecords"] == 2
    assert result["withCoordinates"] == 1


def test_run_sleeps_between_multiple_batches():
    config = make_config()
    raw = [
        {"NAME": "First Baptist Church", "STATE": "ME", "ZIP": "04101", "AFFILIATIO": "9", "X": -70.2, "Y": 43.6},
        {"NAME": "Grace Chapel", "STATE": "TX", "ZIP": "75001", "AFFILIATIO": "9", "X": -70.2, "Y": 43.6},
    ]

    with patch("scraper.hifld_import.fetch_all_records", return_value=raw), patch(
        "scraper.hifld_import.fetch_all_existing_churches", return_value=[]
    ), patch("scraper.hifld_import.submit_batch", return_value=1), patch(
        "scraper.hifld_import.time.sleep"
    ) as sleep:
        result = run(config=config, session=MagicMock(), batch_size=1, batch_delay_s=0.01)

    assert result["created"] == 2
    sleep.assert_called_once_with(0.01)


def test_run_defaults_config_and_session_when_not_given():
    with patch("scraper.hifld_import.BulkImportConfig.from_env", return_value=make_config()), patch(
        "scraper.hifld_import.fetch_all_records", return_value=[]
    ), patch("scraper.hifld_import.fetch_all_existing_churches", return_value=[]):
        result = run()

    assert result == {
        "totalRecords": 0,
        "churchRecords": 0,
        "withCoordinates": 0,
        "afterInternalDedup": 0,
        "new": 0,
        "created": 0,
    }


@responses.activate
def test_submit_batch_still_posts_to_the_shared_bulk_import_endpoint():
    # hifld_import reuses bulk_import.submit_batch directly rather than duplicating
    # it — this just confirms that reuse still hits the expected endpoint/header.
    from scraper.hifld_import import submit_batch

    responses.add(
        responses.POST,
        "https://api.example.com" + BULK_IMPORT_ENDPOINT,
        json={"created": 1},
        status=201,
    )

    created = submit_batch(make_config(), requests_session(), [{"name": "A"}])

    assert created == 1
