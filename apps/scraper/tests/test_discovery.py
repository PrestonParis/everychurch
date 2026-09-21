from unittest.mock import MagicMock

from scraper.discovery import discover_churches, extract_fields
from scraper.tiling import Tile


def test_extract_fields_pulls_expected_keys():
    raw = {
        "place_id": "abc",
        "name": "First Church",
        "vicinity": "123 Main St",
        "geometry": {"location": {"lat": 1.5, "lng": -2.5}},
        "business_status": "OPERATIONAL",
        "types": ["church", "place_of_worship"],
    }

    assert extract_fields(raw) == {
        "place_id": "abc",
        "name": "First Church",
        "address": "123 Main St",
        "lat": 1.5,
        "lon": -2.5,
        "business_status": "OPERATIONAL",
        "types": ["church", "place_of_worship"],
    }


def test_extract_fields_falls_back_to_formatted_address_and_defaults():
    raw = {"place_id": "abc", "formatted_address": "456 Oak Ave"}

    fields = extract_fields(raw)

    assert fields["address"] == "456 Oak Ave"
    assert fields["lat"] is None
    assert fields["lon"] is None
    assert fields["types"] == []


def test_discover_churches_dedupes_results_across_overlapping_tiles():
    client = MagicMock()
    client.search_nearby.side_effect = [
        [{"place_id": "a", "name": "A"}],
        [{"place_id": "a", "name": "A"}, {"place_id": "b", "name": "B"}],
    ]
    tiles = [Tile(lat=1, lon=1, radius_m=1000), Tile(lat=1, lon=1.01, radius_m=1000)]

    results = list(discover_churches(client, radius_m=1000, tiles=tiles))

    assert [r["place_id"] for r in results] == ["a", "b"]
    assert client.search_nearby.call_count == 2


def test_discover_churches_skips_results_without_a_place_id():
    client = MagicMock()
    client.search_nearby.side_effect = [[{"name": "No ID"}]]
    tiles = [Tile(lat=1, lon=1, radius_m=1000)]

    results = list(discover_churches(client, radius_m=1000, tiles=tiles))

    assert results == []


def test_discover_churches_uses_generate_tiles_when_none_given(monkeypatch):
    client = MagicMock()
    client.search_nearby.return_value = []
    fake_tiles = [Tile(lat=1, lon=1, radius_m=1000)]
    monkeypatch.setattr("scraper.discovery.generate_tiles", lambda radius_m: fake_tiles)

    results = list(discover_churches(client, radius_m=1000))

    assert results == []
    client.search_nearby.assert_called_once_with(1, 1, 1000)
