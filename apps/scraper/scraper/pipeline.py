import logging
from typing import Optional

import requests

from .config import ScraperConfig
from .dedup import filter_new
from .discovery import discover_churches
from .enrichment import enrich_from_website
from .existing_churches import fetch_all_existing_churches
from .places_client import PlacesClient
from .regions import STATE_BOUNDING_BOXES, bounding_box
from .run_tracker import ScraperRunClient
from .submission_client import SubmissionClient
from .tiling import generate_tiles

logger = logging.getLogger(__name__)


def run(
    config: Optional[ScraperConfig] = None,
    session: Optional[requests.Session] = None,
    region: Optional[str] = None,
) -> dict:
    """End-to-end scraper run: discover -> dedup -> enrich -> submit.

    This is the Lambda's unit of work (see lambda_handler below) — kept
    separate from the handler so it can be invoked directly in tests/local
    runs without going through an `event`/`context` pair.

    When ``region`` is given, tiling is scoped to that state's bounding box
    (regions.py) instead of sweeping the entire CONUS — lambda_handler always
    passes one, since a full-CONUS sweep from Lambda blows past its 15-minute
    execution ceiling. Local/manual runs may still omit it for today's
    full-sweep behavior, since the caller controls their own timeout there.

    Every call is tracked server-side as a ScraperRun, started here and marked
    COMPLETED or FAILED before returning/raising.
    """
    config = config or ScraperConfig.from_env()
    session = session or requests.Session()

    run_tracker = ScraperRunClient(config.server_base_url, config.scraper_api_key, session=session)
    run_id = run_tracker.start(region)

    try:
        places_client = PlacesClient(config.places_api_key, session=session)

        tiles = None
        if region is not None:
            min_lat, max_lat, min_lon, max_lon = bounding_box(region)
            tiles = generate_tiles(config.search_radius_m, min_lat, max_lat, min_lon, max_lon)

        discovered = list(discover_churches(places_client, config.search_radius_m, tiles=tiles))

        existing = fetch_all_existing_churches(config.server_base_url, session)
        new_churches = filter_new(discovered, existing)

        submissions = [_build_submission(church, places_client, session) for church in new_churches]

        submission_client = SubmissionClient(config.server_base_url, config.scraper_api_key, session=session)
        accepted = submission_client.submit_all(submissions)
    except Exception:
        run_tracker.complete(run_id, "FAILED", None, None, None)
        raise

    result = {"discovered": len(discovered), "new": len(new_churches), "accepted": accepted}
    run_tracker.complete(run_id, "COMPLETED", result["discovered"], result["new"], result["accepted"])
    logger.info("scraper run: %s", result)
    return result


def _build_submission(church: dict, places_client: PlacesClient, session: requests.Session) -> dict:
    payload = dict(church)
    details = places_client.get_details(church["place_id"])
    payload["phone"] = details.get("formatted_phone_number")
    payload["website"] = details.get("website")
    if details.get("formatted_address"):
        payload["address"] = details["formatted_address"]

    if payload.get("website"):
        enriched = enrich_from_website(payload["website"], session)
        payload.update({k: v for k, v in enriched.items() if v})

    return _to_church_dto_shape(payload)


def _to_church_dto_shape(payload: dict) -> dict:
    """Translates this module's internal snake_case/lat-lon field names (from
    discovery.extract_fields and enrichment.enrich_from_website) into the exact
    camelCase names server/.../dto/ChurchDto.java expects on the wire. Jackson
    doesn't auto-translate casing, so without this a mismatched field — zip_code
    vs zipCode, image_url vs imageUrl, lat/lon vs latitude/longitude — doesn't
    error, it just silently vanishes on the way into the review queue.
    """
    return {
        "name": payload.get("name"),
        "address": payload.get("address"),
        "city": payload.get("city"),
        "state": payload.get("state"),
        "zipCode": payload.get("zip_code"),
        "phone": payload.get("phone"),
        "website": payload.get("website"),
        "description": payload.get("description"),
        "imageUrl": payload.get("image_url"),
        "latitude": payload.get("lat"),
        "longitude": payload.get("lon"),
    }


def lambda_handler(event, context) -> dict:
    """Always region-scoped — never a full-CONUS sweep, which would blow past
    Lambda's 15-minute execution ceiling. Uses the region from the event if
    given, otherwise asks the server which candidate needs a run next (oldest-
    or-no COMPLETED run), giving automatic round-robin coverage over all 50
    states with no scheduler-side state needed.
    """
    region = (event or {}).get("region")

    if not region:
        config = ScraperConfig.from_env()
        run_tracker = ScraperRunClient(config.server_base_url, config.scraper_api_key)
        region = run_tracker.next_region(STATE_BOUNDING_BOXES.keys())

    return run(region=region)
