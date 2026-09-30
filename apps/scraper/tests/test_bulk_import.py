import math
from unittest.mock import MagicMock, patch

import pandas as pd
import responses

from scraper.bulk_import import (
    BULK_IMPORT_ENDPOINT,
    BULK_IMPORT_HEADER,
    _clean,
    _denomination_for_row,
    _normalize_denomination,
    _zip_code,
    dedupe_within_batch,
    download_dataset,
    fetch_all_existing_churches,
    filter_christian,
    is_christian,
    load_rows,
    map_rows,
    run,
    submit_batch,
    to_church_dto_shape,
)
from scraper.config import BulkImportConfig


def make_config():
    return BulkImportConfig(bulk_import_api_key="bulk-secret", server_base_url="https://api.example.com")


# --- _clean -------------------------------------------------------------------

def test_clean_returns_none_for_none():
    assert _clean(None) is None


def test_clean_returns_none_for_nan_float():
    assert _clean(float("nan")) is None


def test_clean_returns_none_for_the_not_available_sentinel():
    assert _clean("NOT AVAILABLE") is None


def test_clean_returns_none_for_blank_string():
    assert _clean("   ") is None


def test_clean_strips_and_returns_a_real_value():
    assert _clean("  Grace Church  ") == "Grace Church"


def test_clean_stringifies_non_string_values():
    assert _clean(95819) == "95819"


# --- is_christian ---------------------------------------------------------------

def test_is_christian_true_for_the_christian_subtype():
    assert is_christian({"subtype": "CHRISTIAN", "name": "First Baptist Church"}) is True


def test_is_christian_false_for_a_different_subtype():
    assert is_christian({"subtype": "MUSLIM", "name": "Some Mosque"}) is False


def test_is_christian_excludes_by_name_even_when_subtype_says_christian():
    # a safety net against a mislabeled row, per Prompt 7 — subtype alone isn't trusted blindly
    assert is_christian({"subtype": "CHRISTIAN", "name": "Downtown Synagogue"}) is False


def test_is_christian_falls_back_to_name_keywords_when_subtype_is_missing():
    assert is_christian({"subtype": None, "name": "Grace Chapel"}) is True
    assert is_christian({"subtype": "NOT AVAILABLE", "name": "Random Building"}) is False


def test_filter_christian_keeps_only_matching_rows():
    df = pd.DataFrame([
        {"subtype": "CHRISTIAN", "name": "Grace Church"},
        {"subtype": "MUSLIM", "name": "Local Mosque"},
    ])

    result = filter_christian(df)

    assert list(result["name"]) == ["Grace Church"]


# --- _zip_code / to_church_dto_shape -------------------------------------------

def test_zip_code_zero_pads_new_england_zips():
    assert _zip_code({"zip": 2101}) == "02101"


def test_zip_code_leaves_a_five_digit_zip_unchanged():
    assert _zip_code({"zip": 95819}) == "95819"


def test_zip_code_returns_none_for_missing_zip():
    assert _zip_code({"zip": None}) is None
    assert _zip_code({"zip": float("nan")}) is None


# --- _normalize_denomination ----------------------------------------------------
# Mirrors V8__normalize_denominations_and_case_insensitive_filters.sql's backfill —
# the dataset's raw `denom` codes were passed straight through in the initial
# Prompt 7 pass, which left the site's denomination filter matching almost nothing
# against the real import.

def test_normalize_denomination_maps_known_codes_to_the_site_taxonomy():
    assert _normalize_denomination("PCUSA") == "Presbyterian"
    assert _normalize_denomination("BAPT") == "Baptist"
    assert _normalize_denomination("LDS") == "Latter-day Saint (LDS)"
    assert _normalize_denomination("COC") == "Church of Christ"


def test_normalize_denomination_is_case_insensitive_on_the_code():
    assert _normalize_denomination("pcusa") == "Presbyterian"


def test_normalize_denomination_clears_the_unknown_sentinel():
    assert _normalize_denomination("UNK") is None


def test_normalize_denomination_passes_through_unmapped_and_none_values():
    assert _normalize_denomination("Catholic") == "Catholic"
    assert _normalize_denomination(None) is None


# --- _denomination_for_row -------------------------------------------------------
# The dataset flags Catholic/Orthodox parishes via `cath="Y"`, not `denom` (`denom` is
# "NOT AVAILABLE" for virtually every one of them) — a gap found after the real 42k-row
# bulk import left Catholic churches with a null denomination almost everywhere.

def test_denomination_for_row_maps_a_cath_flagged_row_to_catholic():
    row = {"cath": "Y", "name": "Saint James Roman Catholic Church", "denom": "NOT AVAILABLE"}
    assert _denomination_for_row(row) == "Catholic"


def test_denomination_for_row_detects_eastern_orthodox_by_name_when_cath_flagged():
    row = {"cath": "Y", "name": "Holy Trinity Orthodox Church", "denom": "NOT AVAILABLE"}
    assert _denomination_for_row(row) == "Eastern Orthodox"


def test_denomination_for_row_does_not_treat_orthodox_presbyterian_as_orthodox():
    # prot="Y"/cath="N" in the real dataset for these — genuinely Presbyterian, not
    # Eastern Orthodox, so they must fall through to the ordinary denom-code mapping.
    row = {"cath": "N", "name": "Covenant Orthodox Presbyterian Church", "denom": "PCA"}
    assert _denomination_for_row(row) == "Presbyterian"


def test_denomination_for_row_falls_back_to_denom_code_when_not_cath_flagged():
    row = {"cath": "N", "name": "Fremont Presbyterian Church", "denom": "PCUSA"}
    assert _denomination_for_row(row) == "Presbyterian"


def test_denomination_for_row_handles_a_missing_cath_column():
    row = {"name": "Fremont Presbyterian Church", "denom": "PCUSA"}
    assert _denomination_for_row(row) == "Presbyterian"


def test_to_church_dto_shape_maps_a_cath_flagged_row_to_catholic():
    row = {
        "name": "Saint James Roman Catholic Church",
        "denom": "NOT AVAILABLE",
        "cath": "Y",
        "address": "1 Cathedral Way",
        "city": "Sacramento",
        "state": "CA",
        "zip": 95814,
        "telephone": "916-555-0100",
        "x": -121.5,
        "y": 38.58,
        "subtype": "CHRISTIAN",
    }

    result = to_church_dto_shape(row)

    assert result["denomination"] == "Catholic"


def test_to_church_dto_shape_maps_the_real_dataset_columns():
    row = {
        "name": "Fremont Presbyterian Church",
        "denom": "PCUSA",
        "address": "5770 Carlson Drive",
        "address2": "NOT AVAILABLE",
        "city": "Sacramento",
        "state": "CA",
        "zip": 95819,
        "telephone": "916-452-7132",
        "x": -121.429885,
        "y": 38.567942,
        "subtype": "CHRISTIAN",
    }

    result = to_church_dto_shape(row)

    assert result == {
        "name": "Fremont Presbyterian Church",
        "denomination": "Presbyterian",
        "address": "5770 Carlson Drive",
        "city": "Sacramento",
        "state": "CA",
        "zipCode": "95819",
        "phone": "916-452-7132",
        "latitude": 38.567942,
        "longitude": -121.429885,
    }


def test_to_church_dto_shape_cleans_not_available_and_missing_fields():
    row = {
        "name": "Muhajereen Mosque",
        "denom": "NOT AVAILABLE",
        "address": "185 Folsom Avenue",
        "city": "Hayward",
        "state": "CA",
        "zip": 94544,
        "telephone": float("nan"),
        "x": -122.058762,
        "y": 37.628087,
        "subtype": "MUSLIM",
    }

    result = to_church_dto_shape(row)

    assert result["denomination"] is None
    assert result["phone"] is None


def test_map_rows_applies_to_every_row():
    df = pd.DataFrame([
        {"name": "A", "denom": None, "address": None, "city": None, "state": None, "zip": 60614, "telephone": None, "x": -87.6, "y": 41.9},
        {"name": "B", "denom": None, "address": None, "city": None, "state": None, "zip": 60615, "telephone": None, "x": -87.7, "y": 41.8},
    ])

    result = map_rows(df)

    assert [c["name"] for c in result] == ["A", "B"]
    assert [c["zipCode"] for c in result] == ["60614", "60615"]


# --- dedupe_within_batch --------------------------------------------------------

def test_dedupe_within_batch_drops_repeated_name_and_zip():
    churches = [
        {"name": "Grace Church", "zipCode": "60614"},
        {"name": "grace church", "zipCode": "60614"},
        {"name": "Different Church", "zipCode": "60614"},
    ]

    result = dedupe_within_batch(churches)

    assert result == [
        {"name": "Grace Church", "zipCode": "60614"},
        {"name": "Different Church", "zipCode": "60614"},
    ]


# --- fetch_all_existing_churches -------------------------------------------------

@responses.activate
def test_fetch_all_existing_churches_tolerates_the_old_bare_array_shape():
    responses.add(
        responses.GET,
        "https://api.example.com/api/churches",
        json=[{"name": "A", "zipCode": "1"}],
    )

    result = fetch_all_existing_churches("https://api.example.com/", requests_session())

    assert result == [{"name": "A", "zipCode": "1"}]
    assert len(responses.calls) == 1


@responses.activate
def test_fetch_all_existing_churches_pages_through_the_paginated_shape():
    responses.add(
        responses.GET,
        "https://api.example.com/api/churches",
        json={"content": [{"name": "A"}], "totalPages": 2},
    )
    responses.add(
        responses.GET,
        "https://api.example.com/api/churches",
        json={"content": [{"name": "B"}], "totalPages": 2},
    )

    result = fetch_all_existing_churches("https://api.example.com", requests_session(), page_size=1)

    assert result == [{"name": "A"}, {"name": "B"}]
    assert len(responses.calls) == 2


@responses.activate
def test_fetch_all_existing_churches_stops_early_on_an_empty_page():
    responses.add(
        responses.GET,
        "https://api.example.com/api/churches",
        json={"content": [], "totalPages": 5},
    )

    result = fetch_all_existing_churches("https://api.example.com", requests_session())

    assert result == []
    assert len(responses.calls) == 1


# --- submit_batch ----------------------------------------------------------------

@responses.activate
def test_submit_batch_sends_the_bulk_import_header_and_body():
    responses.add(
        responses.POST,
        "https://api.example.com" + BULK_IMPORT_ENDPOINT,
        json={"created": 2},
        status=201,
    )

    created = submit_batch(make_config(), requests_session(), [{"name": "A"}, {"name": "B"}])

    assert created == 2
    request = responses.calls[0].request
    assert request.headers[BULK_IMPORT_HEADER] == "bulk-secret"
    import json
    assert json.loads(request.body) == {"churches": [{"name": "A"}, {"name": "B"}]}


@responses.activate
def test_submit_batch_includes_source_and_total_new_when_given():
    responses.add(
        responses.POST,
        "https://api.example.com" + BULK_IMPORT_ENDPOINT,
        json={"created": 1},
        status=201,
    )

    submit_batch(make_config(), requests_session(), [{"name": "A"}], source="kaggle", total_new=42)

    import json
    body = json.loads(responses.calls[0].request.body)
    assert body == {"churches": [{"name": "A"}], "source": "kaggle", "totalNew": 42}


@responses.activate
def test_submit_batch_falls_back_to_batch_length_when_response_omits_created():
    responses.add(responses.POST, "https://api.example.com" + BULK_IMPORT_ENDPOINT, json={}, status=201)

    created = submit_batch(make_config(), requests_session(), [{"name": "A"}])

    assert created == 1


# --- download_dataset / load_rows -------------------------------------------------

def test_download_dataset_builds_the_csv_path():
    with patch("scraper.bulk_import.kagglehub.dataset_download", return_value="/tmp/dataset-dir") as download:
        path = download_dataset()

    download.assert_called_once_with("mexwell/us-places-of-worship")
    assert path == "/tmp/dataset-dir/all_places_of_worship.csv"


def test_load_rows_reads_the_csv():
    fake_df = pd.DataFrame([{"name": "A"}])
    with patch("scraper.bulk_import.pd.read_csv", return_value=fake_df) as read_csv:
        result = load_rows("/tmp/some.csv")

    read_csv.assert_called_once_with("/tmp/some.csv")
    assert result is fake_df


# --- run ---------------------------------------------------------------------------

def test_run_orchestrates_download_filter_dedupe_and_ingest():
    config = make_config()
    df = pd.DataFrame([{"name": "A"}])
    christian_df = pd.DataFrame([{"name": "A"}])
    mapped = [{"name": "A", "zipCode": "1"}, {"name": "B", "zipCode": "2"}]

    with patch("scraper.bulk_import.download_dataset", return_value="/tmp/x.csv"), patch(
        "scraper.bulk_import.load_rows", return_value=df
    ), patch("scraper.bulk_import.filter_christian", return_value=christian_df), patch(
        "scraper.bulk_import.map_rows", return_value=mapped
    ), patch(
        "scraper.bulk_import.fetch_all_existing_churches", return_value=[{"name": "b", "zipCode": "2"}]
    ), patch(
        "scraper.bulk_import.submit_batch", return_value=1
    ) as submit, patch(
        "scraper.bulk_import.time.sleep"
    ) as sleep:
        result = run(config=config, session=MagicMock(), batch_size=1)

    # "B" already exists (normalized-key match) so only "A" should ever reach submit_batch
    submit.assert_called_once()
    batch_arg = submit.call_args[0][2]
    assert batch_arg == [{"name": "A", "zipCode": "1"}]
    sleep.assert_not_called()  # only one batch, nothing to delay between
    assert result == {
        "totalRows": 1,
        "christianRows": 1,
        "afterInternalDedup": 2,
        "new": 1,
        "created": 1,
    }


def test_run_sleeps_between_multiple_batches():
    config = make_config()
    mapped = [{"name": "A", "zipCode": "1"}, {"name": "B", "zipCode": "2"}]

    with patch("scraper.bulk_import.download_dataset", return_value="/tmp/x.csv"), patch(
        "scraper.bulk_import.load_rows", return_value=pd.DataFrame()
    ), patch("scraper.bulk_import.filter_christian", return_value=pd.DataFrame()), patch(
        "scraper.bulk_import.map_rows", return_value=mapped
    ), patch(
        "scraper.bulk_import.fetch_all_existing_churches", return_value=[]
    ), patch(
        "scraper.bulk_import.submit_batch", return_value=1
    ), patch(
        "scraper.bulk_import.time.sleep"
    ) as sleep:
        result = run(config=config, session=MagicMock(), batch_size=1, batch_delay_s=0.01)

    assert result["created"] == 2
    sleep.assert_called_once_with(0.01)


def test_run_defaults_config_and_session_when_not_given():
    with patch("scraper.bulk_import.BulkImportConfig.from_env", return_value=make_config()), patch(
        "scraper.bulk_import.download_dataset", return_value="/tmp/x.csv"
    ), patch("scraper.bulk_import.load_rows", return_value=pd.DataFrame()), patch(
        "scraper.bulk_import.filter_christian", return_value=pd.DataFrame()
    ), patch("scraper.bulk_import.map_rows", return_value=[]), patch(
        "scraper.bulk_import.fetch_all_existing_churches", return_value=[]
    ):
        result = run()

    assert result == {
        "totalRows": 0,
        "christianRows": 0,
        "afterInternalDedup": 0,
        "new": 0,
        "created": 0,
    }


def requests_session():
    import requests

    return requests.Session()
