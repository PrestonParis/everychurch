import pytest

from scraper.regions import STATE_BOUNDING_BOXES, bounding_box


def test_all_fifty_states_are_present():
    assert len(STATE_BOUNDING_BOXES) == 50


@pytest.mark.parametrize("code,box", STATE_BOUNDING_BOXES.items())
def test_every_box_is_non_empty_and_sane(code, box):
    min_lat, max_lat, min_lon, max_lon = box

    assert min_lat < max_lat, code
    assert min_lon < max_lon, code
    # roughly the range that could plausibly contain a US state/territory
    assert 15 <= min_lat <= max_lat <= 72, code
    assert -180 <= min_lon <= max_lon <= -60, code


def test_bounding_box_looks_up_a_known_state():
    assert bounding_box("RI") == STATE_BOUNDING_BOXES["RI"]


def test_bounding_box_rejects_an_unknown_code():
    with pytest.raises(ValueError):
        bounding_box("ZZ")
