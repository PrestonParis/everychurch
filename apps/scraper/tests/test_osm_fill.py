import json
from unittest.mock import ANY, MagicMock, patch

import responses

from scraper.config import BulkImportConfig
from scraper.osm_fill import (
    FILL_ENDPOINT,
    SpatialIndex,
    _expand_days,
    _missing,
    _name_score,
    _normalize_name,
    build_fills,
    load_elements,
    main,
    match_known,
    osm_fields,
    parse_service_times,
    run,
    submit_fills,
)


def make_config():
    return BulkImportConfig(bulk_import_api_key="bulk-secret", server_base_url="https://api.example.com")


def node(name, lat=41.82, lon=-71.41, **tags):
    tags["name"] = name
    return {"type": "node", "id": 1, "lat": lat, "lon": lon, "tags": tags}


def known(id, name, lat=41.82, lon=-71.41, state="RI", city=None, **fields):
    return {"id": id, "name": name, "latitude": lat, "longitude": lon, "state": state, "city": city, **fields}


# Roughly 0.0009 degrees of latitude per 100m.
def north_m(meters):
    return 41.82 + meters / 111_000


def index_of(*churches):
    index = SpatialIndex()
    for church in churches:
        index.add(church)
    return index


# --- tag mapping ------------------------------------------------------------------

def test_osm_fields_maps_contact_tags_and_drops_blanks():
    element = node(
        "Grace Church",
        website="www.grace.example.org; https://other.example.org",
        **{
            "contact:phone": "+1 401 555 0100",
            "contact:email": "office@grace.example.org",
            "contact:facebook": "@GraceRI",
            "contact:instagram": "https://instagram.com/graceri",
            "contact:youtube": "https://youtube.com/@graceri",
            "denomination": "baptist",
            "service_times": "Su 10:30",
        },
    )

    assert osm_fields(element) == {
        "website": "http://www.grace.example.org",
        "phone": "+1 401 555 0100",
        "email": "office@grace.example.org",
        "facebook": "https://www.facebook.com/GraceRI",
        "instagram": "https://instagram.com/graceri",
        "youtube": "https://youtube.com/@graceri",
        "denomination": "Baptist",
        "serviceTimes": [{"dayOfWeek": "Sunday", "time": "10:30", "type": "Worship"}],
    }


def test_osm_fields_rejects_malformed_values():
    element = node(
        "Grace Church",
        website="see our facebook page",
        email="not an email",
        facebook="two words",
        youtube="graceri",
        phone=" ; ",
    )

    assert osm_fields(element) == {}


def test_osm_fields_keeps_an_existing_scheme_and_falls_back_to_contact_tags():
    element = node("Grace Church", url="HTTPS://grace.example.org", **{"contact:website": ""})

    assert osm_fields(element) == {"website": "HTTPS://grace.example.org"}


def test_osm_fields_without_tags():
    assert osm_fields({"lat": 1, "lon": 2}) == {}


def test_service_type_prefers_the_known_churchs_denomination():
    element = node("St Mary", service_times="Sa 17:00", denomination="lutheran")

    assert osm_fields(element, "Catholic")["serviceTimes"][0]["type"] == "Mass"
    assert osm_fields(element)["serviceTimes"][0]["type"] == "Worship"


# --- service_times ----------------------------------------------------------------

def test_parse_service_times_expands_days_and_takes_range_starts():
    times = parse_service_times("Su 8:00,10:30-11:30; Sa,Mo 17:00;", "Mass")

    assert times == [
        {"dayOfWeek": "Sunday", "time": "08:00", "type": "Mass"},
        {"dayOfWeek": "Sunday", "time": "10:30", "type": "Mass"},
        {"dayOfWeek": "Saturday", "time": "17:00", "type": "Mass"},
        {"dayOfWeek": "Monday", "time": "17:00", "type": "Mass"},
    ]


def test_parse_service_times_strips_comments_and_open_ended_times():
    times = parse_service_times('Sa 07:20 "daily mass"; Su 09:30+ "Sunday mass"', "Mass")

    assert [(t["dayOfWeek"], t["time"]) for t in times] == [("Saturday", "07:20"), ("Sunday", "09:30")]


def test_parse_service_times_dedupes_overlapping_rules():
    assert len(parse_service_times("Mo-Fr 07:00; We 07:00", "Mass")) == 5


def test_expand_days_wraps_past_sunday():
    assert _expand_days("Sa-Mo,We") == ["Sa", "Su", "Mo", "We"]


def test_parse_service_times_rejects_anything_not_simple():
    for raw in (None, "", "Sunday 10am", "Jun-Aug Su 09:00", "Su 10:00; PH off", "Su 25:00", "Su 10:75", ";"):
        assert parse_service_times(raw, "Worship") is None, raw


def test_parse_service_times_rejects_what_looks_like_opening_hours():
    assert parse_service_times("Mo-Su 06:00,08:00,10:00", "Worship") is None


# --- matching ---------------------------------------------------------------------

def test_name_score_tiers():
    a, b = _normalize_name("First Baptist Church"), _normalize_name("FIRST BAPTIST CHURCH OF SPRINGFIELD INC")
    assert _name_score(a, b, 900) is not None
    assert _name_score(a, b, 1100) is None
    weak_a, weak_b = "grace bible church", "grace bible fellowship"
    assert _name_score(weak_a, weak_b, 100) is not None
    assert _name_score(weak_a, weak_b, 500) is None
    assert _name_score("", b, 0) is None


def test_name_score_ignores_containment_of_a_single_word():
    assert _name_score("church", "grace lutheran church", 10) is None


def test_two_buildings_side_by_side_need_a_name_match():
    index = index_of(known(1, "Trinity Lutheran Church"))

    assert match_known(node("Saint Josephs Catholic Church", lat=north_m(10)), "RI", index) is None


def test_match_known_prefers_the_better_name_then_the_nearer():
    best = known(1, "Grace Baptist Church", lat=north_m(400))
    weaker = known(2, "Grace Baptist Chapel", lat=north_m(50))
    farther_twin = known(3, "Grace Baptist Church", lat=north_m(800))
    index = index_of(best, weaker, farther_twin)

    assert match_known(node("Grace Baptist Church"), "RI", index) is best


def test_match_known_falls_back_to_a_unique_misplaced_exact_name():
    misplaced = known(1, "CALVARY CHAPEL RAMONA", lat=north_m(5000), city="Ramona")
    index = index_of(misplaced, known(2, "Calvary Chapel", lat=north_m(6000), state="MA"))

    assert match_known(node("Calvary Chapel"), "RI", index) is misplaced


def test_match_known_skips_an_ambiguous_misplaced_name():
    index = index_of(known(1, "Church of God", lat=north_m(3000)), known(2, "Church of God", lat=north_m(6000)))

    assert match_known(node("Church of God"), "RI", index) is None


def test_match_known_skips_unnamed_known_churches_far_out():
    index = index_of(known(1, None, lat=north_m(3000)))

    assert match_known(node("Church of God"), "RI", index) is None


# --- build_fills ------------------------------------------------------------------

def test_missing_keeps_only_blank_fields_and_uses_service_summary():
    church = known(1, "A", website="https://a.example.org", phone="  ", serviceSummary="Sundays 9:00 AM")
    fields = {"website": "http://x", "phone": "555", "serviceTimes": [{}]}

    assert _missing(church, fields) == {"phone": "555"}


def test_build_fills_sends_only_missing_fields_per_matched_church():
    existing = [
        known(1, "Grace Church", website="https://grace.example.org"),
        known(2, "Trinity Church", lat=north_m(5000)),
        known(3, "Full Church", lat=north_m(20000), phone="555"),
        {"id": None, "name": "No id", "latitude": 41.82, "longitude": -71.41},
    ]
    elements = [
        ("RI", node("Grace Church", website="other.example.org", phone="401-555-0100")),
        ("RI", node("Trinity Church", lat=north_m(5000), website="trinity.example.org", phone="1")),
        ("RI", node("Trinity Church", lat=north_m(5010), website="trinity.example.org", phone="2")),
        ("RI", node("Full Church", lat=north_m(20000), phone="999")),
        ("RI", node("Nowhere Church", lat=north_m(90000), phone="1")),
        ("RI", node("Bare Church")),
        ("RI", node("Saint Marys Convent", phone="1")),
    ]

    fills, stats = build_fills(elements, existing)

    assert fills == [
        {"id": 1, "phone": "401-555-0100"},
        {"id": 2, "website": "http://trinity.example.org"},
    ]
    assert stats["osmChurches"] == 6
    assert stats["withFillableTags"] == 5
    assert stats["matched"] == 4
    assert stats["unmatched"] == 1
    assert stats["conflictingFields"] == 1
    assert stats["churchesToFill"] == 2
    assert stats["fill.phone"] == 1
    assert stats["fill.website"] == 1


# --- io ---------------------------------------------------------------------------

def _fetch_by_state(session, states):
    return [(state, node(f"{state} Church")) for state in states]


def test_load_elements_saves_after_each_state_and_resumes_after_a_failure(tmp_path):
    cache = tmp_path / "osm.json"

    def fail_on_ma(session, states):
        if states == ["MA"]:
            raise RuntimeError("overpass gave up")
        return _fetch_by_state(session, states)

    with patch("scraper.osm_fill.fetch_all_elements", side_effect=fail_on_ma):
        try:
            load_elements(MagicMock(), ["RI", "MA"], str(cache), sleep=lambda s: None)
        except RuntimeError:
            pass
    assert list(json.loads(cache.read_text())) == ["RI"]

    with patch("scraper.osm_fill.fetch_all_elements", side_effect=_fetch_by_state) as fetch:
        elements = load_elements(MagicMock(), ["RI", "MA"], str(cache), sleep=lambda s: None)
    fetch.assert_called_once_with(ANY, ["MA"])
    assert [state for state, _ in elements] == ["RI", "MA"]

    with patch("scraper.osm_fill.fetch_all_elements") as fetch:
        assert len(load_elements(MagicMock(), ["RI", "MA"], str(cache))) == 2
    fetch.assert_not_called()
    assert not (tmp_path / "osm.json.tmp").exists()


def test_load_elements_reads_the_original_flat_cache_format(tmp_path):
    cache = tmp_path / "osm.json"
    cache.write_text(json.dumps([["RI", node("A")], ["RI", node("B")]]))

    with patch("scraper.osm_fill.fetch_all_elements") as fetch:
        elements = load_elements(MagicMock(), ["RI"], str(cache))
    fetch.assert_not_called()
    assert [e["tags"]["name"] for _, e in elements] == ["A", "B"]


def test_load_elements_without_cache_pauses_between_states():
    sleep = MagicMock()
    with patch("scraper.osm_fill.fetch_all_elements", side_effect=_fetch_by_state):
        elements = load_elements(MagicMock(), ["RI", "MA", "CT"], None, sleep=sleep)
    assert len(elements) == 3
    assert sleep.call_count == 2


@responses.activate
def test_submit_fills_posts_with_the_bulk_key():
    responses.add(responses.POST, f"https://api.example.com{FILL_ENDPOINT}", json={"updated": 3})

    assert submit_fills(make_config(), __import__("requests").Session(), [{"id": 1, "phone": "1"}]) == 3
    request = responses.calls[0].request
    assert request.headers["X-Bulk-Import-Api-Key"] == "bulk-secret"
    assert json.loads(request.body) == {"churches": [{"id": 1, "phone": "1"}], "source": "osm"}


# --- run --------------------------------------------------------------------------

def _patched_run(fills, **kwargs):
    with patch("scraper.osm_fill.load_elements", return_value=[]), \
            patch("scraper.osm_fill.fetch_all_existing_churches", return_value=[known(1, "A")]), \
            patch("scraper.osm_fill.build_fills", return_value=(fills, {"churchesToFill": len(fills)})), \
            patch("scraper.osm_fill.submit_fills", side_effect=lambda c, s, batch: len(batch)) as submit, \
            patch("scraper.osm_fill.time.sleep") as sleep:
        result = run(make_config(), MagicMock(), **kwargs)
    return result, submit, sleep


def test_run_sends_fills_in_batches():
    result, submit, sleep = _patched_run([{"id": i} for i in range(5)], batch_size=2)

    assert result == {"churchesToFill": 5, "updated": 5}
    assert submit.call_count == 3
    assert sleep.call_count == 2


def test_run_dry_run_sends_nothing():
    result, submit, _ = _patched_run([{"id": 1, "phone": "1"}], dry_run=True)

    assert result == {"churchesToFill": 1, "updated": 0}
    submit.assert_not_called()


def test_main_parses_arguments():
    with patch("scraper.osm_fill.run", return_value={}) as run_mock:
        main(["--dry-run", "--cache", "x.json", "--states", "ri, ma"])
        main([])

    assert run_mock.call_args_list[0].kwargs == {"states": ["RI", "MA"], "cache_path": "x.json", "dry_run": True}
    assert run_mock.call_args_list[1].kwargs["dry_run"] is False
