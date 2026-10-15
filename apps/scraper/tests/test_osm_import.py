from unittest.mock import MagicMock, patch
from urllib.parse import unquote_plus

import pytest
import requests
import responses

from scraper.config import BulkImportConfig
from scraper.osm_import import (
    OVERPASS_MAX_RETRIES,
    OVERPASS_STATE_DELAY_S,
    OVERPASS_URL,
    STATE_CODES,
    SpatialIndex,
    _denomination,
    _distance_m,
    _fill_city,
    _is_same_church,
    _normalize_name,
    _zip_code,
    dedupe,
    fetch_all_elements,
    is_church,
    run,
    to_church_dto_shape,
)


def make_config():
    return BulkImportConfig(bulk_import_api_key="bulk-secret", server_base_url="https://api.example.com")


def node(name=None, lat=41.82, lon=-71.41, **tags):
    if name is not None:
        tags["name"] = name
    return {"type": "node", "id": 1, "lat": lat, "lon": lon, "tags": tags}


def church(name, lat=41.82, lon=-71.41, city=None, state="RI"):
    return {"name": name, "latitude": lat, "longitude": lon, "city": city, "state": state}


# Roughly 0.0009 degrees of latitude per 100m.
def north_m(meters):
    return 41.82 + meters / 111_000


# --- STATE_CODES ------------------------------------------------------------------

def test_state_codes_cover_the_fifty_states_plus_dc():
    assert len(STATE_CODES) == 51
    assert "DC" in STATE_CODES


# --- fetch ------------------------------------------------------------------------

@responses.activate
def test_fetch_all_elements_tags_each_element_with_its_queried_state():
    responses.add(responses.POST, OVERPASS_URL, json={"elements": [node("A")]})
    responses.add(responses.POST, OVERPASS_URL, json={"elements": [node("B"), node("C")]})
    sleep = MagicMock()

    result = fetch_all_elements(requests.Session(), states=["RI", "CT"], sleep=sleep)

    assert [(state, e["tags"]["name"]) for state, e in result] == [("RI", "A"), ("CT", "B"), ("CT", "C")]
    assert 'area["ISO3166-2"="US-RI"]' in unquote_plus(responses.calls[0].request.body)
    assert responses.calls[0].request.headers["User-Agent"] == "everychurch-osm-import/1.0"
    # Pauses between states, not before the first one.
    sleep.assert_called_once_with(OVERPASS_STATE_DELAY_S)


@responses.activate
def test_fetch_all_elements_retries_past_an_overpass_overload():
    responses.add(responses.POST, OVERPASS_URL, status=429)
    responses.add(responses.POST, OVERPASS_URL, status=504)
    responses.add(responses.POST, OVERPASS_URL, json={"elements": [node("A")]})

    result = fetch_all_elements(requests.Session(), states=["RI"], sleep=MagicMock())

    assert len(result) == 1


@responses.activate
def test_fetch_all_elements_treats_a_runtime_error_remark_as_a_failure():
    # Overpass reports a query abort as HTTP 200 plus a remark and partial elements.
    responses.add(
        responses.POST,
        OVERPASS_URL,
        json={"elements": [node("partial")], "remark": "runtime error: Query timed out"},
    )
    responses.add(responses.POST, OVERPASS_URL, json={"elements": [node("A"), node("B")]})

    result = fetch_all_elements(requests.Session(), states=["TX"], sleep=MagicMock())

    assert [e["tags"]["name"] for _, e in result] == ["A", "B"]


@responses.activate
def test_fetch_all_elements_raises_after_exhausting_retries():
    for _ in range(OVERPASS_MAX_RETRIES):
        responses.add(responses.POST, OVERPASS_URL, status=504)

    with pytest.raises(RuntimeError, match="TX failed after"):
        fetch_all_elements(requests.Session(), states=["TX"], sleep=MagicMock())


@responses.activate
def test_fetch_all_elements_defaults_its_session():
    responses.add(responses.POST, OVERPASS_URL, json={})

    assert fetch_all_elements(states=["RI"], sleep=MagicMock()) == []


# --- is_church --------------------------------------------------------------------

def test_is_church_true_for_a_named_node():
    assert is_church(node("Barrington Baptist Church")) is True


def test_is_church_uses_a_ways_center_for_coordinates():
    way = {"type": "way", "center": {"lat": 41.8, "lon": -71.4}, "tags": {"name": "Grace Church"}}
    assert is_church(way) is True


def test_is_church_false_without_coordinates():
    assert is_church({"type": "way", "tags": {"name": "Grace Church"}}) is False


def test_is_church_false_without_a_name():
    assert is_church(node()) is False
    assert is_church(node("   ")) is False
    assert is_church({"type": "node", "lat": 1, "lon": 1}) is False


@pytest.mark.parametrize(
    "name",
    [
        "Carmelite Monastery",
        "Franciscan Missionaries of Mary",
        "Saint Marys Home",
        "Saint Marys Home.",
        "Bishop Hendricken High School",
        "Rhode Island Hospital Chapel",
        "Swan Point Cemetery Chapel",
    ],
)
def test_is_church_false_for_non_congregation_places(name):
    assert is_church(node(name)) is False


@pytest.mark.parametrize(
    "name",
    [
        "College Park Baptist Church",
        "University Baptist Church",
        "Camp Hill Presbyterian Church",
        "Home Mission Baptist Church",
        "Homestead Church of God",
        # Exclusion words overridden by a congregation word — all real names the
        # nationwide dry run was wrongly dropping.
        "Faith Lutheran Church and School",
        "Old School Baptist Church",
        "Campground Church",
        "Southampton Old School Baptist Meeting House",
        "Saint Monica Catholic Church and School",
    ],
)
def test_is_church_keeps_real_churches_named_after_places(name):
    assert is_church(node(name)) is True


def test_is_church_false_for_a_wayside_shrine():
    assert is_church(node("Our Lady", place_of_worship="wayside_shrine")) is False


# --- mapping ----------------------------------------------------------------------

@pytest.mark.parametrize(
    "raw, expected",
    [
        ("roman_catholic", "Catholic"),
        ("african_methodist_episcopal", "Methodist"),
        ("evangelical_lutheran", "Lutheran"),
        ("united_church_of_christ", None),
        ("churches_of_christ", "Church of Christ"),
        ("the_church_of_jesus_christ_of_latter-day_saints", "Latter-day Saint (LDS)"),
        ("mormon", "Latter-day Saint (LDS)"),
        ("seventh_day_adventist", "Seventh-day Adventist"),
        ("greek_orthodox", "Eastern Orthodox"),
        ("anglican", "Episcopal/Anglican"),
        ("episcopal", "Episcopal/Anglican"),
        ("presbyterian", "Presbyterian"),
        ("assemblies_of_god", "Pentecostal / Assembly of God"),
        ("pentecostal", "Pentecostal / Assembly of God"),
        ("nondenominational", "Non-denominational"),
        ("non-denominational", "Non-denominational"),
        ("non denominational", "Non-denominational"),
        ("southern_baptist", "Baptist"),
        ("evangelical", "Evangelical"),
        ("Roman_Catholic", "Catholic"),
        ("jehovahs_witness", None),
        (None, None),
        ("", None),
    ],
)
def test_denomination(raw, expected):
    assert _denomination(raw) == expected


@pytest.mark.parametrize(
    "raw, expected",
    [("02806", "02806"), ("02806-1234", "02806"), ("2806", None), ("RI 02806", None), (None, None)],
)
def test_zip_code(raw, expected):
    assert _zip_code(raw) == expected


def test_to_church_dto_shape_maps_osm_tags():
    element = node(
        "Barrington Baptist Church",
        denomination="baptist",
        **{
            "addr:housenumber": "25",
            "addr:street": "Old County Road",
            "addr:city": "Barrington",
            "addr:postcode": "02806",
            "phone": "+1 401 555 0100",
            "website": "https://example.org",
        },
    )

    assert to_church_dto_shape("RI", element) == {
        "name": "Barrington Baptist Church",
        "denomination": "Baptist",
        "address": "25 Old County Road",
        "city": "Barrington",
        "state": "RI",
        "zipCode": "02806",
        "phone": "+1 401 555 0100",
        "website": "https://example.org",
        "latitude": 41.82,
        "longitude": -71.41,
    }


def test_to_church_dto_shape_falls_back_to_contact_tags_and_a_street_without_number():
    element = node(
        "Grace Church",
        **{"addr:street": "Main Street", "contact:phone": "555", "contact:website": "https://g.org"},
    )

    shape = to_church_dto_shape("RI", element)

    assert shape["address"] == "Main Street"
    assert shape["phone"] == "555"
    assert shape["website"] == "https://g.org"


def test_to_church_dto_shape_leaves_missing_address_fields_blank():
    shape = to_church_dto_shape("TX", node("Grace Church"))

    assert shape["address"] is None
    assert shape["city"] is None
    assert shape["zipCode"] is None
    assert shape["state"] == "TX"


# --- matching ---------------------------------------------------------------------

def test_normalize_name_folds_common_filing_variations():
    assert _normalize_name("ST. JOHN'S Church, Inc.") == "saint john s church"
    assert _normalize_name("Mt Zion & Grace") == "mount zion and grace"
    assert _normalize_name(None) == ""


def test_distance_m_is_roughly_right():
    assert _distance_m(41.82, -71.41, north_m(100), -71.41) == pytest.approx(100, rel=0.01)


def test_is_same_church_true_for_anything_nearly_on_top():
    assert _is_same_church("grace church", "kingdom hall", 20) is True


def test_is_same_church_false_beyond_the_strong_radius():
    assert _is_same_church("grace church", "grace church", 1500) is False


def test_is_same_church_true_for_a_strong_name_match_far_from_the_point():
    # HIFLD points are sometimes a pastor's-house filing address, not the building.
    assert _is_same_church("saint john s episcopal church", "saint johns episcopal church", 800) is True


def test_is_same_church_true_when_one_name_contains_the_other():
    assert _is_same_church("grace", "grace community church", 500) is True


def test_is_same_church_weak_name_match_only_counts_when_close():
    a, b = "first baptist church warren", "baptist church warren"
    assert _is_same_church(a + " ri", b, 100) is True
    assert _is_same_church("first baptist warren", "baptist church of warren", 400) is False


def test_is_same_church_false_for_different_names_nearby():
    assert _is_same_church("grace episcopal church", "saint mary catholic church", 100) is False


def test_is_same_church_false_when_a_name_is_blank():
    assert _is_same_church("", "grace church", 100) is False


# --- spatial index / dedupe -------------------------------------------------------

def test_spatial_index_ignores_churches_without_coordinates():
    index = SpatialIndex()
    index.add({"name": "No coords", "latitude": None, "longitude": None})

    assert index.nearby(41.82, -71.41, 1000) == []


def test_spatial_index_finds_churches_across_grid_cell_boundaries():
    index = SpatialIndex()
    index.add(church("Grace Church", lat=0.0199, lon=0.0199))

    assert len(index.nearby(0.0201, 0.0201, 100)) == 1


def test_spatial_index_handles_high_latitudes():
    index = SpatialIndex()
    index.add(church("Anchorage Church", lat=89.0, lon=10.0))

    assert len(index.nearby(89.0, 10.01, 1000)) == 1


def test_dedupe_drops_candidates_matching_a_known_church():
    known = [church("ST JOHNS EPISCOPAL CHURCH INC")]
    candidates = [church("Saint John's Episcopal Church", lat=north_m(300)), church("New Hope Church", lat=north_m(5000))]

    assert [c["name"] for c in dedupe(candidates, known)] == ["New Hope Church"]


def test_dedupe_collapses_a_node_and_building_outline_of_the_same_church():
    candidates = [church("Grace Church"), church("Grace Church", lat=north_m(40))]

    assert len(dedupe(candidates, [])) == 1


def test_dedupe_drops_an_exact_name_match_to_a_known_church_a_few_km_away():
    # HIFLD sometimes places a church at its IRS mailing address, not the building.
    known = [church("MOUNT OLIVET BAPTIST CHURCH", lat=north_m(6000))]

    assert dedupe([church("Mount Olivet Baptist Church")], known) == []


def test_dedupe_drops_a_known_church_whose_filed_name_adds_its_town():
    known = [church("CALVARY CHAPEL RAMONA", lat=north_m(2500), city="RAMONA")]

    assert dedupe([church("Calvary Chapel")], known) == []


def test_dedupe_keeps_a_distant_same_name_known_church_in_another_state():
    known = [church("MOUNT OLIVET BAPTIST CHURCH", lat=north_m(3000), state="MA")]

    assert len(dedupe([church("Mount Olivet Baptist Church")], known)) == 1


def test_dedupe_keeps_a_distant_known_church_that_only_resembles_the_name():
    known = [
        church("GRACE BAPTIST CHURCH", lat=north_m(3000), city="WARREN"),
        church("", lat=north_m(3000)),
    ]

    assert len(dedupe([church("Grace Church")], known)) == 1


def test_dedupe_never_drops_a_nameless_candidate_on_the_exact_name_rule():
    known = [church("", lat=north_m(3000))]

    assert len(dedupe([church("")], known)) == 1


def test_dedupe_keeps_same_named_osm_churches_a_few_km_apart():
    # The exact-name rule is for known churches only; OSM's own points are accurate.
    candidates = [church("Church of God"), church("Church of God", lat=north_m(5000))]

    assert len(dedupe(candidates, [])) == 2


def test_dedupe_keeps_same_named_churches_in_different_towns():
    # The failure mode name+ZIP dedup would have with ZIP-less OSM rows.
    candidates = [church("First Baptist Church"), church("First Baptist Church", lat=north_m(20_000))]

    assert len(dedupe(candidates, [])) == 2


# --- city fill --------------------------------------------------------------------

def test_fill_city_leaves_an_existing_city_alone():
    index = SpatialIndex()
    index.add(church("Other", city="Warren"))

    assert _fill_city(church("Grace", city="Bristol"), index)["city"] == "Bristol"


def test_fill_city_borrows_the_nearest_same_state_church_city():
    index = SpatialIndex()
    index.add(church("Far", lat=north_m(1200), city="Warren"))
    index.add(church("Near", lat=north_m(300), city="Barrington"))
    index.add(church("No city", lat=north_m(10)))
    index.add(church("Other state", lat=north_m(5), city="Seekonk", state="MA"))

    assert _fill_city(church("Grace"), index)["city"] == "Barrington"


def test_fill_city_stays_blank_with_nothing_close_enough():
    index = SpatialIndex()
    index.add(church("Far", lat=north_m(3000), city="Warren"))

    assert _fill_city(church("Grace"), index)["city"] is None


# --- run --------------------------------------------------------------------------

def test_run_orchestrates_fetch_filter_dedupe_fill_and_ingest():
    elements = [
        ("RI", node("Barrington Baptist Church", lat=41.74)),
        ("RI", node("Carmelite Monastery", lat=41.75)),
        ("RI", node("Saint John's Church", lat=41.9)),
    ]
    existing = [
        church("ST JOHNS CHURCH", lat=41.9, city="Providence"),
        church("Some Other Church", lat=41.7405, city="Barrington"),
    ]

    with patch("scraper.osm_import.fetch_all_elements", return_value=elements), patch(
        "scraper.osm_import.fetch_all_existing_churches", return_value=existing
    ), patch("scraper.osm_import.submit_batch", return_value=1) as submit, patch(
        "scraper.osm_import.time.sleep"
    ) as sleep:
        result = run(config=make_config(), session=MagicMock(), batch_size=10)

    batch = submit.call_args[0][2]
    assert [(c["name"], c["city"]) for c in batch] == [("Barrington Baptist Church", "Barrington")]
    assert submit.call_args.kwargs == {"source": "osm", "total_new": 1}
    sleep.assert_not_called()
    assert result == {
        "totalElements": 3,
        "churchElements": 2,
        "new": 1,
        "cityFilled": 1,
        "cityMissing": 0,
        "created": 1,
    }


def test_run_sleeps_between_multiple_batches():
    elements = [("RI", node("Grace Church", lat=41.0)), ("RI", node("Hope Church", lat=42.0))]

    with patch("scraper.osm_import.fetch_all_elements", return_value=elements), patch(
        "scraper.osm_import.fetch_all_existing_churches", return_value=[]
    ), patch("scraper.osm_import.submit_batch", return_value=1), patch("scraper.osm_import.time.sleep") as sleep:
        result = run(config=make_config(), session=MagicMock(), batch_size=1, batch_delay_s=0.01)

    assert result["created"] == 2
    assert result["cityMissing"] == 2
    sleep.assert_called_once_with(0.01)


def test_run_defaults_config_and_session_when_not_given():
    with patch("scraper.osm_import.BulkImportConfig.from_env", return_value=make_config()), patch(
        "scraper.osm_import.fetch_all_elements", return_value=[]
    ), patch("scraper.osm_import.fetch_all_existing_churches", return_value=[]):
        result = run()

    assert result["new"] == 0
