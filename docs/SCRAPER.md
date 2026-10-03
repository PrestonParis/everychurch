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

## OpenStreetMap import (2026-09-30)

*Implemented* — `scraper/osm_import.py`, same trusted `POST /api/scraper/bulk-import` endpoint and `BULK_IMPORT_API_KEY` as the other two imports. Run manually, **after** `hifld_import`: `python -m scraper.osm_import`.

**Why:** HIFLD only has congregations that filed with the IRS (see "Known ceiling" above). OSM is mapped by people on the ground, not IRS-gated, so it reaches small and independent churches HIFLD never will. Free, no API key: one Overpass query per state (`amenity=place_of_worship` + `religion=christian`, `out center tags`), 51 queries total with a pause between each, retried on the public instance's routine 429/504s.

**What a Rhode Island probe showed (555 elements):** 96% named, 61% `denomination`-tagged, but only 37% carry `addr:city`/`addr:postcode`. That drives the two design decisions below.

**Spatial dedup, not name+ZIP.** With most rows ZIP-less, name+ZIP would collapse every ZIP-less "First Baptist Church" in the country into one. Every OSM row and every HIFLD/Kaggle row has coordinates, so `_is_same_church` matches on name similarity scaled by distance: anything within 30m is the same place; a close name match (≥0.85, or one name containing the other) within 1km; a looser one (≥0.6) only within 150m. The 1km allowance exists because HIFLD points are geocoded from the IRS filing address, which is sometimes a pastor's house or PO box rather than the building. **Order matters:** run HIFLD first, or OSM's sparser rows get imported for churches HIFLD would have supplied with a full address.

**City fill.** The directory's city search is a text match on `city`, so a blank city makes a church invisible to it (Near Me still works — that's coordinate-based). `_fill_city` borrows the city of the nearest known church in the same state within 1.5km. ZIP is deliberately not borrowed — ZIP boundaries are much finer, so a neighbor's is wrong far more often.

**Filtering:** unnamed elements and roadside shrines/crosses are dropped, plus religious houses and institutional chapels by name (monastery, convent, school, hospital, cemetery, a name ending in "Home", …). Unlike HIFLD's list, "college"/"university"/"camp" are not excluded — in OSM they're place names ("College Park Baptist Church").

**Licensing (ODbL):** the client must show "© OpenStreetMap contributors" wherever OSM-sourced churches appear, and the church table is covered by ODbL share-alike.

## OpenStreetMap backfill (2026-10-01)

*Implemented* — `scraper/osm_fill.py` → `POST /api/scraper/bulk-import/fill` (same `BULK_IMPORT_API_KEY`). Run manually after the imports:

```sh
python -m scraper.osm_fill --dry-run --cache osm_elements.json   # match + report + 25 sample fills, sends nothing
python -m scraper.osm_fill --cache osm_elements.json             # reuses the download, sends the fills
```

**Why:** the import dropped every OSM church that matched an existing HIFLD/Kaggle row — and with it the website/phone its mapper had tagged. HIFLD has no phone or website column, so those rows are blank. This matches each OSM church to its existing row and fills website, phone, email, Facebook/Instagram/YouTube, denomination, and service times (parsed from the `service_times` tag; anything beyond simple `Su 08:00,10:30; Sa 17:00` rules is skipped whole, never half-parsed).

**Stricter matching than the import's dedup** — a wrong fill puts another church's website on this one, so proximity alone (the import's 30m rule) never counts; there must be a name match (see the module docstring for the tiers). Conflicting values from two OSM elements on one church are dropped.

**Never overwrites.** `ChurchService.fillBlanks` only sets blank fields, adds service times only when the church has none, never touches name/state/coordinates, and skips claimed churches. Safe to re-run.

**Expected yield (Rhode Island probe, 530 OSM churches):** 155 with a website, 144 a phone, 297 a denomination, but only 26 with `service_times` (23 parseable). This fills contact info well; service times come from the website crawl below.

## Wikidata backfill (2026-10-01)

*Implemented* — `scraper/wikidata_fill.py`, same `/fill` endpoint, matching (`osm_fill.match_known`) and blanks-only/never-claimed guarantees as the OSM backfill. Run after `osm_fill` (OSM's values win wherever both have one, simply by getting there first):

```sh
python -m scraper.wikidata_fill --dry-run
python -m scraper.wikidata_fill
```

**What it adds:** official website, denomination, Facebook/Instagram/YouTube, phone, email, and a photo (`imageUrl`) — for *notable* churches only (~11.9k US church buildings with coordinates). A 1,500-item live sample had something usable on 636: 313 websites, 271 denominations, 230 photos. A property with two values (two websites, two phones) is skipped rather than guessed.

**Querying:** the Wikidata Query Service kills queries at 60s. "Every US church" only finishes in one join order (church-building subclasses → instances → US), forced with `hint:optimizer "None"` (~10s, ids + coordinates only); details follow in 500-id batches. No API key, no rate-limit account.

**Licensing — photos are not CC0.** Wikidata's *data* is CC0, but photos live on Wikimedia Commons, and ~70% need attribution (mostly CC BY-SA). The client has nowhere to show a photo credit, so only images Commons marks `AttributionRequired=false` (CC0 / public domain) are used, as 320px thumbnails (the profile shows an 80px avatar). Adding a credit line to the client would unlock the rest.

**No 8km "misplaced" fallback:** that rule needs the item's state, and Wikidata's comes from a P131 location chain that's too slow to query nationwide — so Wikidata matching is the within-1km name-match tiers only.

## Denomination backfill from names (2026-10-03)

*Implemented* — `scraper/denomination_fill.py`, same `/fill` endpoint and blanks-only/never-claimed guarantees. Labels a church with a blank denomination from its own name, using the HIFLD import's ordered name patterns (`hifld_import._DENOMINATION_NAME_PATTERNS`):

```sh
python -m scraper.denomination_fill --dry-run   # report + 25 sample fills, sends nothing
python -m scraper.denomination_fill
```

The patterns now cover the bodies the first pass missed, bucketed the same way as `bulk_import.DENOMINATION_CODE_MAP`: Nazarene, C&MA, Vineyard and Calvary Chapel go to Evangelical; Church of God, Apostolic, Foursquare and COGIC go to Pentecostal; Wesleyan and AME/CME go to Methodist; Brethren, Mennonite, Reformed, UCC, Disciples and Quaker go to Other. Spanish names are covered too (Bautista, Metodista, Iglesia de Dios, ...). Order matters and is documented in the code, e.g. "African Methodist Episcopal" is Methodist and "United Church of Christ" is not Church of Christ. A name that matches nothing stays blank.

**Fixed with it (2026-10-03):** the old patterns labelled "United Church of Christ" congregations as Church of Christ. `V21__relabel_united_church_of_christ.sql` moves those to Other. The "Church of Christ" search filter also never matched its own label (it looked only for a raw `COC` code), so it returned nothing and those churches showed under Other. `ChurchRepository.search` now matches the label.

## Capitalization (2026-10-03)

HIFLD files every name, street and city in capitals. `NameCase` (server) gives them ordinary capitalization: "ST JAMES AME CHURCH OF THE VALLEY" becomes "St James AME Church of the Valley", and "920 3RD ST SE" becomes "920 3rd St SE". Acronyms (AME, LDS, PO, NE, Roman numerals) and a trailing state code stay upper-case. Only a value with no lowercase letter is touched; anything mixed-case was cased by a person or a better source. Claimed churches are skipped.

- New bulk imports are fixed on the way in (`ChurchService.createImported`).
- Existing rows: `POST /api/scraper/bulk-import/normalize-case?afterId=0&limit=1000` (bulk-import key) fixes one id-ordered batch and returns `{scanned, updated, nextAfterId}`. Call again with `nextAfterId` until it is null. Idempotent.
- Reverting is exact, since every converted value was all-caps: `UPDATE churches SET name = UPPER(name), ...`.

## Website crawl backfill (2026-10-02)

*Implemented* — `scraper/website_fill.py`, same `/fill` endpoint and blanks-only/never-claimed guarantees as the other backfills. Fills **service times, pastor and description** by reading each church's own website. Free (no API key, no per-call cost), unlike Places enrichment. Run it **after** `osm_fill` and `wikidata_fill`, since it only visits churches that already have a website:

```sh
python -m scraper.website_fill --dry-run --cache website_crawl.jsonl --states RI   # crawl + report + 25 sample fills
python -m scraper.website_fill --cache website_crawl.jsonl                         # reuses the crawl, sends the fills
```

`--cache` appends one JSON line per church as it goes. An interrupted run resumes where it stopped, and the real run reuses a dry run's crawl. A church whose website has changed since is crawled again. `--workers` (default 16) is how many sites are crawled at once; `--limit` caps a trial run.

**What it reads:** the homepage plus up to 4 same-site pages picked by link text/URL: two about services ("Mass Times", "Worship", "Plan a Visit") and two about the pastor ("Our Pastor", "Staff", "About"). A dedicated service-times page wins over the homepage; schedules from different pages are never merged.

**Service times — precision over recall.** A time counts only when its line names a service ("Sunday Worship 9 & 10:30 AM", "Saturday Vigil 5pm") or is a bare "Sunday 10:00 am" under a heading that does. It needs an am/pm (or 24h); a bare "10:30" is too ambiguous. Rejected:
- office hours, Sunday School/classes, confession/adoration/rosary, prayer meetings and meals;
- dated events ("Oct 4"), ranges ("9am-2pm"), holiday/summer/Lent schedules, monthly ("1st Sunday") and relative ("after the 10am service") times, TV/cable broadcasts.

A "Sunday" heading carries down to the bare times under it. Two-column layouts (labels in one block, times in the next) are rejected outright if any label is excluded. Some sites list several churches (a merged parish), either under subheadings ("St. Mary of the Assumption") or inline ("10:30am @ St. Mary"). There, only the times placed at *this* church count. A schedule with no Saturday/Sunday service is dropped as probably partial. Type is Mass for Catholic churches or "Mass" wording, Bible Study when the line says so, otherwise Worship. A language named next to a time ("9am English, 11am Spanish") is kept.

**Pastor:** "Pastor Jane Doe", "Rev. John Smith, Senior Pastor", or a staff card (name and "Senior Pastor" on adjacent lines; the page's layout decides above vs. below). Associate/youth/music/etc. pastors never count. Two different lead-pastor names means none, not a guess ("Senior/Lead Pastor" breaks a tie).

**Description:** the site's own JSON-LD / meta / OpenGraph description, if it reads like one: not a builder template ("Just another WordPress site"), not truncated "...", not a phone/email/address line, not a "A | B | C" title.

**Guards against filling the wrong church:**
- the homepage must mention the church's name, so lapsed domains now showing a parking page are skipped;
- a website shared by several churches (diocese, denomination or national site) is skipped for all of them;
- a description found on more than one church's site is dropped as boilerplate;
- social/profile "websites" (Facebook, Instagram, Linktree, jw.org, ...) are never crawled.

**Polite crawling:** honest User-Agent (`everychurch-website-fill/1.0 (+https://everychurch.co)`), robots.txt honoured. A robots.txt that errors is treated as disallow-all. A Crawl-delay is honoured, and above 10s only the homepage is read. One request at a time per site, with a 1s pause. HTML only, pages capped at 2MB.

**Yield (150 random OSM church websites nationwide, live, 2026-10-02):** 120 reachable (14 down, 10 blocked by robots.txt or a 403, 6 failed the name check). Of the 150: service times 49%, pastor 28%, description 35%. 150 sites took 75s at 16 workers. The ~26k OSM church websites nationwide would take about 3.5 hours (more once Wikidata websites are in). Several rounds of hand-checking against the live pages (RI, MA, TX, OH, GA, NY, PA, IL, CA, FL, NC, WA, MI, VA, MN) drove the rules above. Known gaps: JavaScript-only sites render no text, schedules split into image/PDF bulletins are skipped, and a Saturday-morning daily Mass satisfies the "has a weekend service" check.

## Local development

```sh
npx nx test scraper        # pytest — scraper/ is fully covered (--cov-fail-under=100)
```

Environment variables (see `.env.example`): `GOOGLE_PLACES_API_KEY`, `SCRAPER_API_KEY`, `SERVER_BASE_URL`, `SCRAPER_SEARCH_RADIUS_M`; for bulk import only: `BULK_IMPORT_API_KEY` (plus optional `KAGGLE_USERNAME`/`KAGGLE_KEY`). Against production, `SERVER_BASE_URL` is the Cloud Run API (`https://everychurch-server-274948593192.us-east1.run.app`), not the everychurch.co website — see [COMMANDS.md](COMMANDS.md) "Production URLs".

The Lambda entry point is `scraper.index.lambda_handler`, which delegates to `pipeline.run()` (discover → dedup → enrich → submit). Locally:

```python
from scraper.pipeline import run
run()               # full CONUS sweep, reads config from the environment variables above
run(region="RI")    # scoped to one state instead — see regions.py for the full list of codes
```

Running a real discovery pass costs Google Places API quota — a full CONUS sweep at the default 25km radius is thousands of Nearby Search calls plus one Place Details call per newly-discovered church, so don't run it against a real API key without narrowing the bounding box first — either `region=` (a single state) or explicit `min_lat`/`max_lat`/`min_lon`/`max_lon` passed to `tiling.generate_tiles` directly.
