from typing import Iterable, Iterator, Optional, Set

from .places_client import PlacesClient
from .tiling import Tile, generate_tiles


def extract_fields(raw_place: dict) -> dict:
    """Pulls the raw fields discovery cares about out of one Nearby Search
    result. Phone/website aren't available here — see PlacesClient.get_details.
    """
    location = raw_place.get("geometry", {}).get("location", {})
    return {
        "place_id": raw_place.get("place_id"),
        "name": raw_place.get("name"),
        "address": raw_place.get("vicinity") or raw_place.get("formatted_address"),
        "lat": location.get("lat"),
        "lon": location.get("lng"),
        "business_status": raw_place.get("business_status"),
        "types": raw_place.get("types", []),
    }


def discover_churches(
    places_client: PlacesClient,
    radius_m: int,
    tiles: Optional[Iterable[Tile]] = None,
) -> Iterator[dict]:
    """Discovers churches across the continental US.

    Adjacent search tiles overlap on purpose (see tiling.generate_tiles), so
    the same place can surface more than once — this dedupes by place_id
    before yielding.
    """
    seen_place_ids: Set[str] = set()

    for tile in tiles if tiles is not None else generate_tiles(radius_m):
        for raw_place in places_client.search_nearby(tile.lat, tile.lon, tile.radius_m):
            place_id = raw_place.get("place_id")
            if not place_id or place_id in seen_place_ids:
                continue
            seen_place_ids.add(place_id)
            yield extract_fields(raw_place)
