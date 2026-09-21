"""Approximate bounding boxes for the 50 US states, used to scope a scraper run to
one state at a time (see pipeline.run's ``region`` parameter) instead of sweeping
the entire CONUS bounding box in a single Lambda invocation.

Boxes are generous rectangles, not precise state borders — adjacent states'
boxes overlap slightly at their edges, which just means occasional duplicate
discovery of the same place near a state line. discover_churches already dedupes
by place_id, so that's harmless. Alaska and Hawaii get their own boxes (unlike
tiling.py's CONUS default, which deliberately excludes them); Alaska's box is
clipped to the mainland/panhandle (roughly -170 to -129 longitude) rather than
its full extent, since the westernmost Aleutians cross the antimeridian and
break simple min/max longitude comparisons.

Format: {state_code: (min_lat, max_lat, min_lon, max_lon)}.
"""

STATE_BOUNDING_BOXES = {
    "AL": (30.2, 35.0, -88.5, -84.9),
    "AK": (51.0, 71.5, -170.0, -129.0),
    "AZ": (31.3, 37.0, -114.9, -109.0),
    "AR": (33.0, 36.5, -94.7, -89.6),
    "CA": (32.5, 42.0, -124.5, -114.1),
    "CO": (36.9, 41.0, -109.1, -102.0),
    "CT": (40.9, 42.1, -73.8, -71.8),
    "DE": (38.4, 39.9, -75.8, -75.0),
    "FL": (24.4, 31.0, -87.7, -80.0),
    "GA": (30.3, 35.0, -85.7, -80.8),
    "HI": (18.9, 22.3, -160.3, -154.8),
    "ID": (41.9, 49.1, -117.3, -111.0),
    "IL": (36.9, 42.6, -91.6, -87.0),
    "IN": (37.7, 41.8, -88.1, -84.7),
    "IA": (40.3, 43.6, -96.7, -90.1),
    "KS": (36.9, 40.1, -102.1, -94.5),
    "KY": (36.4, 39.2, -89.6, -81.9),
    "LA": (28.8, 33.1, -94.1, -88.7),
    "ME": (42.9, 47.5, -71.2, -66.8),
    "MD": (37.8, 39.8, -79.6, -74.9),
    "MA": (41.1, 43.0, -73.6, -69.8),
    "MI": (41.6, 48.4, -90.5, -82.1),
    "MN": (43.4, 49.5, -97.3, -89.4),
    "MS": (30.1, 35.1, -91.7, -88.0),
    "MO": (35.9, 40.7, -95.9, -89.0),
    "MT": (44.3, 49.1, -116.1, -104.0),
    "NE": (39.9, 43.1, -104.1, -95.2),
    "NV": (34.9, 42.1, -120.1, -113.9),
    "NH": (42.6, 45.4, -72.6, -70.6),
    "NJ": (38.8, 41.4, -75.6, -73.8),
    "NM": (31.2, 37.1, -109.1, -102.9),
    "NY": (40.4, 45.1, -79.9, -71.7),
    "NC": (33.7, 36.7, -84.4, -75.4),
    "ND": (45.9, 49.1, -104.1, -96.5),
    "OH": (38.3, 42.0, -84.9, -80.4),
    "OK": (33.5, 37.1, -103.1, -94.4),
    "OR": (41.9, 46.4, -124.7, -116.4),
    "PA": (39.7, 42.3, -80.6, -74.6),
    "RI": (41.1, 42.1, -71.9, -71.0),
    "SC": (32.0, 35.3, -83.5, -78.4),
    "SD": (42.4, 46.0, -104.1, -96.4),
    "TN": (34.9, 36.7, -90.4, -81.6),
    "TX": (25.8, 36.6, -106.7, -93.4),
    "UT": (36.9, 42.1, -114.1, -109.0),
    "VT": (42.7, 45.1, -73.5, -71.4),
    "VA": (36.5, 39.5, -83.7, -75.1),
    "WA": (45.5, 49.1, -124.9, -116.9),
    "WV": (37.1, 40.7, -82.7, -77.6),
    "WI": (42.4, 47.1, -92.9, -86.4),
    "WY": (40.9, 45.1, -111.1, -104.0),
}


def bounding_box(region: str) -> tuple:
    """Looks up a state's bounding box, raising a clear error for an unknown code
    rather than silently sweeping the wrong (or entire) area.
    """
    try:
        return STATE_BOUNDING_BOXES[region]
    except KeyError:
        raise ValueError(f"Unknown region code: {region!r}") from None
