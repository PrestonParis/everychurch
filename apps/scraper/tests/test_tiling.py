import pytest

from scraper.tiling import Tile, generate_tiles


def test_generate_tiles_covers_the_full_bounding_box():
    tiles = list(
        generate_tiles(radius_m=500_000, min_lat=30, max_lat=31, min_lon=-90, max_lon=-88)
    )

    assert all(isinstance(t, Tile) for t in tiles)
    assert all(30 <= t.lat <= 31 for t in tiles)
    assert all(-90 <= t.lon <= -88 for t in tiles)
    assert tiles[0].lat == 30
    assert tiles[0].lon == -90
    assert all(t.radius_m == 500_000 for t in tiles)


def test_generate_tiles_narrower_lon_step_near_the_equator_than_near_the_pole():
    equator_tiles = list(
        generate_tiles(radius_m=100_000, min_lat=0, max_lat=0.5, min_lon=-10, max_lon=10)
    )
    polar_tiles = list(
        generate_tiles(radius_m=100_000, min_lat=80, max_lat=80.5, min_lon=-10, max_lon=10)
    )

    # Longitude degrees are "shorter" near the poles, so covering the same
    # lon range takes fewer, more widely-spaced tiles there.
    assert len(polar_tiles) < len(equator_tiles)


def test_generate_tiles_rejects_non_positive_radius():
    with pytest.raises(ValueError):
        list(generate_tiles(radius_m=0))

    with pytest.raises(ValueError):
        list(generate_tiles(radius_m=-1))


def test_generate_tiles_single_point_when_box_smaller_than_one_tile():
    tiles = list(
        generate_tiles(radius_m=1_000_000, min_lat=30, max_lat=30, min_lon=-90, max_lon=-90)
    )

    assert tiles == [Tile(lat=30, lon=-90, radius_m=1_000_000)]
