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

## Bulk import (Kaggle "us-places-of-worship")

*Implemented (Prompt 7)* — `scraper/bulk_import.py`, server side `BulkImportController` + `BulkImportApiKeyFilter`. A one-time/occasional seed, **not** part of `pipeline.run()` or the Lambda. Run manually: `python -m scraper.bulk_import` (needs `BULK_IMPORT_API_KEY`, `SERVER_BASE_URL`).

**Dataset findings (inspected, not guessed — version 1, 49,328 rows):** `subtype` is a clean religion category on every row (`CHRISTIAN` 42,697; `JUDAIC` 3,256; `BUDDHIST` 1,711; `MUSLIM` 1,254; `HINDU` 282; `OTHER` 128). `x`/`y` are longitude/latitude. `zip` is stored as an int, dropping leading zeros on ~4,051 New England rows (re-zero-padded to 5 digits). The dataset's null convention is the literal string `"NOT AVAILABLE"` (except `telephone`, which uses real NaN). It has no website/email/description. It contains no Jehovah's Witness entries (0 "witness" name matches — the few "JEHOVAH …" names are Baptist churches); LDS is present (2,117 rows) and correctly under `CHRISTIAN`. It has 65 internal duplicate name+zip rows.

**Filtering:** primary filter is `subtype == CHRISTIAN`. On top of that, a name-keyword safety net *excludes* obvious non-Christian names (mosque, synagogue, temple, gurdwara, masjid, shul) even when `subtype` says CHRISTIAN, and *falls back* to church/chapel/cathedral/parish/ministry keywords if `subtype` were ever missing (not observed).

**Mapping/dedup:** columns are mapped to `ChurchDto`'s camelCase shape (`denom`→`denomination`, `telephone`→`phone`, `x`/`y`→`longitude`/`latitude`). Supplying coordinates means `ChurchService.create` trusts them and skips geocoding — no Geocoding API quota is burned. Rows are deduped within the dataset, then against the server via the same normalized name+zip key as `dedup.py`. The existing-church set is built by paging `GET /api/churches` (`page`/`size`), tolerating both the paginated `{content, totalPages}` shape and the older bare array; no unpaginated public endpoint was reintroduced.

**Denomination:** `denom` is a raw dataset abbreviation code (`"BAPT"`, `"SBC"`, `"AME"`, ...), not the site's `DENOMINATIONS` taxonomy — `DENOMINATION_CODE_MAP`/`_normalize_denomination` maps it at ingest time (`V8__normalize_denominations_and_case_insensitive_filters.sql` backfilled the rows already imported before this map existed; best-effort, see that migration's per-code comments). Catholic and Orthodox parishes are a separate case: the dataset flags them via a boolean `cath` column, not `denom` (`denom` is `"NOT AVAILABLE"` for essentially every one of them — confirmed 0 rows anywhere in the dataset have `"CATH"` in `denom`) — `_denomination_for_row` checks `cath == "Y"` first and maps to `"Catholic"`, or `"Eastern Orthodox"` when the name says so (`"Orthodox Presbyterian"` rows are `prot="Y"`/`cath="N"` in this dataset and correctly fall through to their own `denom` code instead). The initial 2026-09-22 import predates this check and left ~6,877 Catholic/Orthodox churches with a null denomination; `docs/DATA_MODEL.md` "Church.denomination" notes the one-off SQL backfill that fixed the already-imported rows to match.

**Ingestion — a trusted path, not the review queue.** Pushing tens of thousands of rows through `ChurchSubmission` review is impractical, so `POST /api/scraper/bulk-import` (`{"churches": [...]}` → `201 {"created": n}`) creates unclaimed `Church` rows directly via `ChurchService.create`. Because it writes without review, it is gated by its **own** secret, `BULK_IMPORT_API_KEY` (header `X-Bulk-Import-Api-Key`, role `ROLE_BULK_IMPORTER`), independently revocable from `SCRAPER_API_KEY`; neither key works on the other's endpoint (403). Two trust tiers now exist: **reviewed** (volunteer submissions + live scraper discovery, via `ChurchSubmission`) vs. **trusted bulk seed** (this import). Anything imported this way is still fully subject to the ordinary `Claim` workflow afterward — only initial creation skips review.

**Resumability:** no checkpoint file — a re-run after a partial failure re-dedupes against what's already imported. Batches (`DEFAULT_BATCH_SIZE = 100`) are separated by `DEFAULT_BATCH_DELAY_S` and progress is logged per batch.

**Kaggle auth:** this dataset downloaded fine anonymously via `kagglehub`; if a download ever fails, set `KAGGLE_USERNAME`/`KAGGLE_KEY` (or `~/.kaggle/kaggle.json`). Python floor was raised to 3.9 because `kagglehub` requires it.

## HIFLD live import (2026-09-27 source survey)

*Implemented* — `scraper/hifld_import.py`, reusing the server's existing `POST /api/scraper/bulk-import` (same trusted-tier endpoint and `BULK_IMPORT_API_KEY` as the Kaggle import above). Run manually: `python -m scraper.hifld_import` (needs `BULK_IMPORT_API_KEY`, `SERVER_BASE_URL`).

**Why a second source:** after the Kaggle bulk import, the directory sat at ~42,000 churches. Checked four candidates before building this — Wikidata (12,544 US church buildings via a live SPARQL query — too sparse, only *notable* churches), OpenStreetMap (~120–150k Christian places of worship extrapolated from a real Colorado Overpass query — a good secondary/cross-check source, not pursued yet), Google Places API (already wired up in `pipeline.py`, but $32/1,000 Nearby Search + $5–20/1,000 Place Details calls makes bulk volume expensive — kept as an enrichment path, not primary), and this one.

**The live HIFLD "All Places of Worship" ArcGIS service is the same government lineage the Kaggle dataset was mirrored from, at a much larger and more current vintage** — confirmed via a direct row-count query: 254,742 total records live vs. 49,328 in the Kaggle snapshot. But the live service **dropped the Kaggle mirror's clean `subtype`/`denom`/`cath`/`prot` columns entirely**; what's left is a rawer, direct pull from the IRS Exempt Organizations Business Master File (fields: `EIN`, `NAME`, `STREET`, `CITY`, `STATE`, `ZIP`, `AFFILIATION`, `ACTIVITY`, `ORGANIZATION`, `NTEE_CD`, `X`/`Y`, ~54 fields total, no `subtype`/religion flag, no phone). A same-state sample turned up a Catholic high school, a "Maine Council of Churches," a Bible society, and a parachurch youth ministry mixed in with real churches, none flagged by any column — this dataset needed its own classification logic, not bulk_import.py's simpler name-keyword-on-top-of-a-subtype-flag filter.

**Classification is built on the real IRS EO BMF code definitions** (Publication 5926, `irs.gov/pub/irs-pdf/p5926.pdf` — read directly, not guessed), not the Kaggle dataset's ad-hoc columns:
- **`AFFILIATION` code** (`_CONGREGATION_AFFILIATIONS` in `hifld_import.py`): 1/2 = central/intermediate organization of a national grouping, 6/7 = group-ruling parent/intermediate, 8 = group-ruling parent that IS a church or 501(c)(1) (a diocese, a denominational headquarters) — none of those five are an individual congregation, they're the organizational body *above* one. Only 3 (independent) and 9 (subordinate — an individual congregation under someone else's group ruling, e.g. a parish under its diocese) are real local congregations. A missing `AFFILIATION` doesn't fail the row — plenty of real churches have it blank.
- **Name-keyword filtering** (`_EXCLUDE_NAME_KEYWORDS`/`_INCLUDE_NAME_KEYWORDS`) is the *only* religion signal here (there's no `subtype` column to lean on, unlike the Kaggle import), so it does more work: excludes other faiths' place-of-worship words plus the non-congregation entity types actually observed in the raw pool (school/academy/seminary, council/association/convention of churches, diocese/archdiocese/conference/synod/presbytery, foundation, cemetery, bible society).
- **Denomination** (`_denomination_from_name`): there's no `denom`/`cath`/`prot` column at all here, so it's a best-effort keyword guess against the organization's own filed name (e.g. `"catholic"` → `Catholic`, `"baptist"` → `Baptist`). Left `None` (not `"Other"`) when nothing matches — same convention as the Kaggle import's denomination handling, and the client's own `denomStyle` already treats a blank denomination as unclassified rather than forcing it into `"Other"`.

**Fetching:** the service supports standard ArcGIS `resultOffset`/`resultRecordCount` pagination, capped at `maxRecordCount = 2000` per page (confirmed via the layer's own `?f=json` metadata) — `fetch_all_records` pages through the whole table this way (~128 requests at the 2026-09 row count) rather than a separate download-and-filter-locally step, since the table is small enough for that to be simpler.

**Known ceiling — documented by the IRS itself, not inferred:** the source Business Master File explicitly excludes "Churches ... that are not required under the Code to file an application and that have not filed an application for recognition of exemption" (Publication 5926). Churches get automatic 501(c)(3) status without ever applying (IRC 508(c)(1)(A)), and many small/independent congregations never do — no amount of filtering recovers those rows, they were never in the source data. This import grows the directory substantially but doesn't reach the true ~350–400k US church estimate on its own; OpenStreetMap (human-mapped, not IRS-filing-gated) is the natural next source to close more of that gap.

**Reuses, doesn't duplicate:** `dedupe_within_batch` and `submit_batch` are imported directly from `bulk_import.py` rather than copied — both are generic over the `{name, zipCode}`-keyed `ChurchDto` shape, and this module already imports `bulk_import` for them (see that module's docstring for why `pipeline.py`, unlike this one, deliberately avoids that import — this is a manual one-time script too, so the same Lambda-payload-weight concern doesn't apply).

## Local development

```sh
npx nx test scraper        # pytest — scraper/ is fully covered (--cov-fail-under=100)
```

Environment variables (see `.env.example`): `GOOGLE_PLACES_API_KEY`, `SCRAPER_API_KEY`, `SERVER_BASE_URL`, `SCRAPER_SEARCH_RADIUS_M`; for bulk import only: `BULK_IMPORT_API_KEY` (plus optional `KAGGLE_USERNAME`/`KAGGLE_KEY`).

The Lambda entry point is `scraper.index.lambda_handler`, which delegates to `pipeline.run()` (discover → dedup → enrich → submit). Locally:

```python
from scraper.pipeline import run
run()               # full CONUS sweep, reads config from the environment variables above
run(region="RI")    # scoped to one state instead — see regions.py for the full list of codes
```

Running a real discovery pass costs Google Places API quota — a full CONUS sweep at the default 25km radius is thousands of Nearby Search calls plus one Place Details call per newly-discovered church, so don't run it against a real API key without narrowing the bounding box first — either `region=` (a single state) or explicit `min_lat`/`max_lat`/`min_lon`/`max_lon` passed to `tiling.generate_tiles` directly.
