from dataclasses import dataclass
from math import cos, pi, radians
from typing import Iterator

# Continental US bounding box (lower 48 only). Alaska/Hawaii and territories
# would need their own bounding boxes — the dateline and Alaska's high
# latitude break the flat lat/lon-degree math used below.
CONUS_MIN_LAT = 24.5
CONUS_MAX_LAT = 49.4
CONUS_MIN_LON = -124.8
CONUS_MAX_LON = -66.9

EARTH_RADIUS_M = 6_371_000
MIN_LON_COMPRESSION = 0.01  # clamps cos(lat) near the poles so lon steps stay finite


@dataclass(frozen=True)
class Tile:
    lat: float
    lon: float
    radius_m: int


def generate_tiles(
    radius_m: int,
    min_lat: float = CONUS_MIN_LAT,
    max_lat: float = CONUS_MAX_LAT,
    min_lon: float = CONUS_MIN_LON,
    max_lon: float = CONUS_MAX_LON,
) -> Iterator[Tile]:
    """Tiles a lat/lon bounding box with circular search regions of ``radius_m``.

    Row and column spacing is ``radius_m`` (not ``2 * radius_m``), so adjacent
    circles overlap slightly rather than leaving gaps at tile corners —
    discovery dedupes the resulting duplicate results by place id.
    """
    if radius_m <= 0:
        raise ValueError("radius_m must be positive")

    lat_step_deg = _meters_to_lat_degrees(radius_m)

    lat = min_lat
    while lat <= max_lat:
        lon_step_deg = _meters_to_lon_degrees(radius_m, lat)
        lon = min_lon
        while lon <= max_lon:
            yield Tile(lat=round(lat, 6), lon=round(lon, 6), radius_m=radius_m)
            lon += lon_step_deg
        lat += lat_step_deg


def _meters_to_lat_degrees(meters: float) -> float:
    return (meters / EARTH_RADIUS_M) * (180 / pi)


def _meters_to_lon_degrees(meters: float, at_lat: float) -> float:
    compression = max(cos(radians(at_lat)), MIN_LON_COMPRESSION)
    return _meters_to_lat_degrees(meters) / compression
