# Scraper / Data Pipeline

## Overview

`apps/scraper` is a Python app that runs as a scheduled AWS Lambda (`scraper.index.lambda_handler`). It has two jobs, both implemented end to end, including the server side:

1. **Discovery** — find churches that aren't in the database yet, via geographic traversal of the Google Places API.
2. **Enrichment** — for a discovered church's website, pull structured data (description, address, socials) the same way the server's `UrlParserService` already does for the "add church from URL" feature.

The scraper never writes to the database directly. It always submits through the server's `ChurchSubmission` verification queue (see [DATA_MODEL.md](DATA_MODEL.md)), so scraped data is reviewed by a Platform Admin exactly like a volunteer's submission before it goes live.

## Discovery pipeline

*Implemented (Prompt 16)* — `scraper/tiling.py`, `scraper/places_client.py`, `scraper/discovery.py`.

**API:** Google Places API — Nearby Search for discovery, Place Details for the phone/website fields Nearby Search doesn't return.

**Tiling:** `tiling.generate_tiles` walks a lat/lon grid over a bounding box — the continental US by default (`CONUS_MIN_LAT`/`MAX_LAT`/`MIN_LON`/`MAX_LON`), or a single state's box when a region is given (see "Region-scoped runs" below). Row/column spacing equals the search radius, so adjacent circular search regions overlap slightly rather than leaving gaps at the corners of the grid; longitude spacing is widened per row by `1/cos(latitude)` so tiles stay roughly the same physical size as latitude increases.

**Pagination/rate limiting:** `PlacesClient.search_nearby` follows `next_page_token` across pages (`NEXT_PAGE_DELAY_S` delay before use — Google's token needs a moment to "warm up"), and retries `OVER_QUERY_LIMIT`/transient errors with linear backoff up to `MAX_RETRIES` before raising `PlacesApiError`.

**Dedup within discovery:** `discovery.discover_churches` dedupes by `place_id` across tiles, since overlapping search circles return the same place more than once.

**Raw fields extracted per result** (`discovery.extract_fields`): `place_id`, `name`, `address` (vicinity or formatted address), `lat`/`lon`, `business_status`, `types`.

## Scrape-to-verification-queue flow

*Implemented, both sides* — scraper: `scraper/enrichment.py`, `scraper/dedup.py`, `scraper/submission_client.py`, `scraper/pipeline.py`. Server: `ScraperSubmissionController`, `ScraperApiKeyFilter`, `ChurchSubmissionService.submitFromScraper`.

**Enrichment:** for each newly-discovered church, `PlacesClient.get_details` fetches phone/website, then `enrichment.enrich_from_website` fetches that website and extracts description/address/socials the same way the server's `UrlParserService` does — JSON-LD (`@type` Church/LocalBusiness/Organization) first, falling back to OpenGraph/meta tags. Enrichment is best-effort: a failed fetch or unparseable page yields `{}` rather than blocking the submission.

**Dedup against existing churches:** before enrichment, `dedup.filter_new` fetches `GET /api/churches` and drops any discovered church whose normalized name+zip already matches an existing `Church` row. This is a cheap pre-filter, not the source of truth — exact-match edge cases (same church, slightly different name) still flow into the review queue below for a human to resolve. It's also a soft filter in practice, not just in principle: a freshly-discovered church has no zip until enrichment finds one on its website (Nearby Search doesn't return one), so pre-enrichment dedup effectively falls back to name-only matching for most results — acceptable given a human reviews every submission anyway.

**Wire format:** `pipeline._to_church_dto_shape` translates this module's internal snake_case field names (`zip_code`, `image_url`) and `lat`/`lon` into the exact camelCase names `server/.../dto/ChurchDto.java` expects (`zipCode`, `imageUrl`, `latitude`, `longitude`) immediately before submission. This exists because Jackson doesn't auto-translate casing — without it, those fields don't error, they just silently don't make it into the submission.

**Ingestion endpoint contract:**

```
POST /api/scraper/church-submissions
Header: X-Scraper-Api-Key: <SCRAPER_API_KEY>
Body:   {"submissions": [<ChurchDto-shaped payload>, ...]}
Response: 202 {"accepted": <int>}
```

Each submission creates a `ChurchSubmission` row with `submittedBy = "scraper"`, `status = PENDING`, reviewed by a Platform Admin exactly like a volunteer's edit — the scraper never writes `Church` rows directly (see ARCHITECTURE.md). `SubmissionClient` batches requests (`DEFAULT_BATCH_SIZE = 25`) to keep payloads small.

**Service-to-service auth:** a static `SCRAPER_API_KEY` shared secret sent as `X-Scraper-Api-Key`, checked by `ScraperApiKeyFilter` and gated by `SecurityConfig`'s `hasRole("SCRAPER")` rule on `/api/scraper/**` — not a user JWT, since the scraper has no `User` row to authenticate as (`ChurchSubmission.submittedBy` stores the literal string `"scraper"`). A missing or wrong key gets 401; a valid *user* JWT (even a Platform Admin's) gets 403 on this path, since it carries `ROLE_PLATFORM_ADMIN`/etc., never `ROLE_SCRAPER`.

## Region-scoped runs & run tracking

*Implemented (Prompt 5, Prompt 6 — see docs/PROMPTS.md)* — scraper: `scraper/regions.py`, `scraper/run_tracker.py`, `pipeline.run`'s `region` parameter, `pipeline.lambda_handler`. Server: `ScraperRun`, `ScraperRunController` (`/api/scraper/runs/**`), `AdminScraperRunController` (`/api/admin/scraper-runs/**`), `ScraperRunService`, `ScraperInvoker`/`LoggingScraperInvoker`.

**Why:** a full-CONUS sweep is already documented above as "thousands of Nearby Search calls" — far past a single Lambda invocation's 15-minute execution ceiling at real (250k+ church) scale. Runs are chunked to one US state at a time instead.

**`regions.py`:** a static `{state_code: (min_lat, max_lat, min_lon, max_lon)}` dict for all 50 states (approximate rectangles — adjacent states' boxes overlap slightly, which just means occasional duplicate discovery near a state line; `discover_churches`'s existing place_id dedup absorbs that). Alaska's box is clipped to the mainland/panhandle to avoid antimeridian-crossing longitude math.

**`pipeline.run(config=None, session=None, region=None)`:** when `region` is given, tiling is scoped to that state's box instead of the CONUS default. Every call — region-scoped or not — is tracked server-side: `ScraperRunClient.start(region)` posts a `ScraperRun` (`status=RUNNING`) before discovery begins, and the run is marked `COMPLETED` with final `discovered`/`newCount`/`accepted` counts on success, or `FAILED` (via a `try`/`except` around the whole body) if anything raises — the exception still propagates after that.

**`lambda_handler(event, context)`:** always region-scoped, never a full-CONUS sweep. Uses `event["region"]` if the caller (e.g. a scheduler) provided one; otherwise asks the server which of the 50 states needs a run next via `GET /api/scraper/runs/next-region?candidates=...` — whichever has the oldest-or-no `COMPLETED` run, giving automatic round-robin coverage with no scheduler-side state. Local/manual calls to `pipeline.run()` directly may still omit `region` for a full-CONUS sweep, since the caller controls their own timeout there.

**Server run-tracking endpoints** (auth: `X-Scraper-Api-Key`, same as the submissions endpoint):

```
POST  /api/scraper/runs                          start a run — {"region": "RI" | null} -> ScraperRunDto (id, status=RUNNING, ...)
PATCH /api/scraper/runs/{id}                      complete/fail a run — {"status": "COMPLETED"|"FAILED", "discovered", "newCount", "accepted"}
GET   /api/scraper/runs/next-region?candidates=.. pick the next region to run -> {"region": "XX"}
```

**Admin visibility & manual trigger** (`/api/admin/scraper-runs/**`, `PLATFORM_ADMIN` only, no scraper key involved):

```
GET  /api/admin/scraper-runs           list all runs, most recent first
POST /api/admin/scraper-runs/trigger   {"region": "RI"} or {} to auto-pick -> {"run": ScraperRunDto, "message": "..."}
```

`trigger` does **not** invoke a real Lambda — none is deployed yet (see docs/PROMPTS.md Prompt 6's scope note: that needs AWS credentials and explicit sign-off, not a code-writing pass). It records a `RUNNING` `ScraperRun` via the same start path and returns a message telling a human to run the scraper locally to fulfil it. `ScraperInvoker` is the seam for a future `LambdaScraperInvoker` (`LambdaClient.invoke(...)`) to swap in later without `ScraperRunService` changing.

## Local development

```sh
npx nx test scraper        # pytest — scraper/ is fully covered (--cov-fail-under=100)
```

Environment variables (see `.env.example`): `GOOGLE_PLACES_API_KEY`, `SCRAPER_API_KEY`, `SERVER_BASE_URL`, `SCRAPER_SEARCH_RADIUS_M`.

The Lambda entry point is `scraper.index.lambda_handler`, which delegates to `pipeline.run()` (discover → dedup → enrich → submit). Locally:

```python
from scraper.pipeline import run
run()               # full CONUS sweep, reads config from the environment variables above
run(region="RI")    # scoped to one state instead — see regions.py for the full list of codes
```

Running a real discovery pass costs Google Places API quota — a full CONUS sweep at the default 25km radius is thousands of Nearby Search calls plus one Place Details call per newly-discovered church, so don't run it against a real API key without narrowing the bounding box first — either `region=` (a single state) or explicit `min_lat`/`max_lat`/`min_lon`/`max_lon` passed to `tiling.generate_tiles` directly.
