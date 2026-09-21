# Architecture

## Overview

EveryChurch is an Nx monorepo with three apps that deploy and scale independently:

| App | Tech | Purpose |
|---|---|---|
| `apps/client` | Angular 21, Tailwind CSS | Public + church-admin frontend SPA |
| `server` | Spring Boot 4 (Java 21) | REST API, owns the database |
| `apps/scraper` | Python, AWS Lambda | Church discovery + data ingestion pipeline |

## System Diagram

```
                     ┌─────────────────┐
   Visitor/Volunteer │  apps/client     │
   (browser)  ──────▶│  Angular SPA     │
                     └────────┬─────────┘
                              │ HTTPS / JSON (/api/**)
                              ▼
                     ┌─────────────────┐         ┌──────────────────┐
                     │  server          │◀───────│  apps/scraper     │
                     │  Spring Boot API │  bulk   │  AWS Lambda        │
                     │                  │  ingest │  (scheduled)       │
                     └────────┬─────────┘         └──────────────────┘
                              │ JDBC
                              ▼
                     ┌─────────────────┐
                     │  PostgreSQL      │
                     │  (Neon)          │
                     └─────────────────┘
```

The scraper does not talk to the database directly — it submits discovered churches to the server over HTTP so every write goes through the same validation and verification-queue path as volunteer- and user-submitted data (see [SCRAPER.md](SCRAPER.md)).

## Data Model

> See [DATA_MODEL.md](DATA_MODEL.md) for the full entity-by-entity breakdown. `User`, `Claim`, `ChurchStaff`, `ChurchSubmission`, `ChurchImage`, `Event`, `Sermon`, `Member`, and `Message` are all implemented; `Article`, `Survey`/`Question`/`Response`, and `Report` are still planned (Faith Formation and Vetting haven't started).

## Auth & Permissions Model

**Decision: stateless JWT auth**, issued by the Spring Boot API on login/registration, sent by the Angular client as an `Authorization: Bearer` header via an `HttpInterceptor`. No server-side session state — this keeps the API easy to scale horizontally. The Lambda scraper's service-to-service calls don't use this mechanism at all — it has no `User` row to log in as, so it authenticates via a separate static shared-secret header instead (`ScraperApiKeyFilter` — see "Authorization baseline" below and SCRAPER.md for the scraper's side of the contract).

Rationale over session-based auth: the client is a separate SPA origin from the API in local dev (proxy.conf.json) and will likely be a separate origin in production too (static hosting + API host), which makes cookie-based sessions more fragile (SameSite/CORS) than a bearer token the interceptor attaches explicitly.

**Social/OAuth login:** not in this pass. Email + password (BCrypt-hashed) is the only path for now. OAuth for church leaders (Google/Facebook) is a plausible fast-follow once the core User/Role model ships, but adding it now would be building ahead of a concrete need — revisit after Prompt 6 lands.

**Role model:**

| Role | Who | Persisted? |
|---|---|---|
| Visitor | Anyone unauthenticated | No — implicit, not a DB value |
| Member | A user who joined a specific church for networking | Yes, via church membership (see DATA_MODEL.md `Member`) |
| Volunteer | A user authorized to submit/edit church listings pending review | Yes, `Role.VOLUNTEER` on `User` |
| Church Admin | Owns or staffs a claimed church; fine-grained permissions (edit profile, manage sermons, manage events, manage staff) are stored per-church on `ChurchStaff`, not on the global role | Yes, `Role.CHURCH_ADMIN` on `User`, scoped via `ChurchStaff` rows |
| Platform Admin | EveryChurch staff; moderation, claim approval, content authoring | Yes, `Role.PLATFORM_ADMIN` on `User` |

A single `User` can hold one global `Role` plus zero or more `ChurchStaff`/`Member` rows scoping them to specific churches. "Church Admin" is a capability tier, not itself a permission set — permission checks always resolve through the per-church `ChurchStaff.permissions`, so a Church Admin for Church A has no implicit access to Church B.

**Authorization baseline:** `GET /api/churches/**`, `GET /api/events/**`, and `/api/auth/**` are public — *except* the congregation-private sub-resources `/api/churches/{id}/staff`, `/members` and `/messages`, which are listed as authenticated ahead of that rule; `/ws/**` is open at the HTTP layer because the WebSocket handshake can't carry a JWT (see "Group chat"); `/api/admin/**` requires `ROLE_PLATFORM_ADMIN`; everything else requires a valid JWT at minimum (`SecurityConfig`, `JwtAuthenticationFilter`). Church-scoped write endpoints (`ChurchController`, `ChurchStaffController`, `ChurchImageController`, `EventController`, `SermonController`) go further and check ownership through `Claim`/`ChurchStaff`: once a church is claimed, editing needs `EDIT_PROFILE` (or a more specific permission like `MANAGE_EVENTS`/`MANAGE_SERMONS`) and deleting the church itself needs to be the owner specifically — a `PLATFORM_ADMIN` bypasses all of these. An *unclaimed* church has no owner yet, so it stays open to any authenticated user until someone claims it.

**403 vs 401 on a real server.** Spring Security answers "logged in but not allowed" with `sendError(403)`; the servlet container then re-dispatches the request to `/error` as an `ERROR`-type dispatch. If that internal dispatch is itself authorized it's anonymous (the JWT filter doesn't run again), fails, and the entry point overwrites the 403 with a **401** — so every permission failure looked like an expired session to real clients (which matters: a client treats 401 as "log in again"). `SecurityConfig` therefore permits `DispatcherType.ERROR`. This was invisible to every MockMvc test, because MockMvc never performs the container's error dispatch; `RealContainerStatusCodeTest` exercises it over a real socket. Prefer that style for any test whose point is the actual HTTP status.

**Volunteer onboarding (Prompt 7):** volunteers are recruited in person — tabling, church visits — not through self-service signup, and the `VOLUNTEER` role carries real privileges (submitting church data into the verification queue). `POST /api/auth/register` therefore always issues `Role.MEMBER`; there is no public path to `VOLUNTEER`. Instead, a Platform Admin generates a single-use, expiring `VolunteerInvite` code (`POST /api/admin/volunteer-invites`, `ROLE_PLATFORM_ADMIN` only) and hands it to the recruit, who redeems it via `POST /api/auth/register/volunteer` (code + email + password) to create a `User` with `Role.VOLUNTEER` directly. `/api/admin/**` requires `ROLE_PLATFORM_ADMIN`; since nothing seeds a Platform Admin otherwise, `AdminUserSeeder` creates one on startup from `ADMIN_EMAIL`/`ADMIN_PASSWORD` env vars if none exists yet — same pattern as the church `DataLoader`, but gated on "no admin exists" rather than "table is empty."

## Geocoding

**Decision (Prompt 14): Google Geocoding API**, consistent with the Google Maps usage described elsewhere in the brief. `GoogleGeocodingService` (`server/src/main/java/com/everychurch/server/service`) resolves a church's address to `latitude`/`longitude` on create/update; the key is read from `GOOGLE_GEOCODING_API_KEY` (`google.geocoding.api-key`). Unset in dev/test on purpose — the service just returns empty rather than throwing, so churches still save without coordinates and no live network call is needed to run the test suite.

`GET /api/churches/nearest?lat=&lng=&radiusMeters=&limit=` is now backed by **PostGIS** (see "Database" below): `churches.geo_point` (a `geography(Point, 4326)` column, kept in sync with `latitude`/`longitude` by `ChurchRepository.updateGeoPoint` right after every save) is ordered with the `<->` KNN operator against a GiST index (`ChurchRepository.findNearest`), replacing the old in-application Haversine sort. That in-memory approach worked fine at a handful of seed churches but doesn't scale to the real target (250k+ churches nationwide) — sorting every geocoded row on every request is the exact bottleneck PostGIS's spatial index exists to avoid. `radiusMeters` is optional: omitted, it's "N closest regardless of distance" (the original behavior); set, it also excludes anything farther than that, via `ST_DWithin`. A church with no coordinates has no `geo_point` and so never appears, same exclusion as before.

The volunteer field kit's `GET /r/nearest` redirect (Prompt 15) reuses this same lookup (`limit=1`) rather than duplicating the distance calculation.

## Volunteer field kit (Prompt 15)

**Decision: printable Angular page (CSS `@media print`), not a generated PDF.** `apps/client/src/app/pages/volunteer-field-kit` renders the flyer as an ordinary component and relies on the browser's print dialog — no PDF-rendering dependency (client or server) and the printout always matches what's shown on screen, at the cost of layout being print-engine-dependent rather than pixel-exact. QR generation uses the `qrcode` npm package (client-side, `QRCode.toDataURL`).

The flow: the printed QR code encodes a link to `/go` (`NearestRedirectComponent`), which reads the *scanning device's* location via the browser Geolocation API and full-page-navigates to the server's public `GET /r/nearest?lat=&lng=` (see "Geocoding" above), which 302s to that church's profile. The redirect target is built from `app.client-base-url` (`CLIENT_BASE_URL`) rather than a relative path, since `/r/nearest` is a plain server redirect with no client-side router context to redirect within. No login is required anywhere in this path — that's the point of a tabling tool.

## Group chat

**Decision (Prompts 13–15): WebSocket via Spring's STOMP support**, not polling — group chat implies real-time delivery, and Spring's WebSocket support is mature enough that it isn't meaningfully more work than a polling endpoint we'd want to replace later. One chat per church (the church *is* the channel — see `Message` in DATA_MODEL.md). Membership (`Member`) is the access boundary.

**Two channels, on purpose.** WebSocket is for *live delivery only*; history is an ordinary paged REST call (`GET /api/churches/{id}/messages?before=&limit=`, keyed by message-id cursor). So a client that just opened the chat, or just reconnected, backfills over HTTP and then listens live. The client re-loads the latest page on every (re)connect and merges by id, so a message that arrives both live and in that reload shows once.

**Wire protocol** (`server/.../config/WebSocketConfig.java`, `controller/ChatController.java`): endpoint `/ws` (raw WebSocket at `/ws/websocket`, SockJS fallback also served at `/ws`); clients `SEND` JSON `{"body": "..."}` to `/app/churches/{id}/chat.send`, and everyone subscribed to `/topic/churches/{id}/chat` receives the persisted `MessageDto`. A message is saved (transaction committed) *before* it's broadcast, so nobody can receive something that then isn't in history. A simple in-memory broker is enough with a single server process.

**Auth is at the STOMP layer, not the HTTP layer** (`security/StompAuthChannelInterceptor.java`). A browser won't attach an `Authorization` header to a WebSocket handshake, so `/ws/**` is `permitAll` in `SecurityConfig` and the real gate is the interceptor:
- `CONNECT` must carry `Authorization: Bearer <jwt>` as a **STOMP header**; it's validated exactly like the HTTP filter does.
- `SUBSCRIBE` is only allowed to `/topic/churches/{id}/chat`, and only if `MemberService.canAccessGroup`. Without this check any logged-in user could subscribe to any church's chat and read it.
- `SEND` is only allowed to `/app/**`. The simple broker relays a client `SEND` to `/topic/**` to every subscriber *as-is*, skipping `ChatController` — no persistence, no membership check — so a member could otherwise forge messages into the room.
Refusing a frame makes Spring answer with a STOMP `ERROR` frame and close the socket. `ChatFlowTest` covers each of these, and each guard was verified load-bearing by disabling it and watching the matching test fail.

**Message rules** (`ChatService`): trimmed, non-empty, ≤ 2000 characters. STOMP has no per-message equivalent of an HTTP status, so a rejected message (not a member, blank, too long) is logged and dropped rather than answered; the log line carries the reason and never the message body.

**Client** (`apps/client/.../services/chat.service.ts`, `pages/church-profile/church-chat.component.*`): uses `@stomp/stompjs` over the **raw WebSocket** endpoint rather than SockJS. Every supported browser has WebSocket, and skipping `sockjs-client` avoids a legacy CommonJS dependency that needs a `global` shim under the esbuild-based Angular builder — the deviation from the original prompt, which listed both. The server still serves SockJS if a fallback is ever needed. The STOMP client is built through an injection token (`STOMP_CLIENT_FACTORY`) so unit tests can swap in a fake. The dev proxy forwards `/ws` with `ws: true`.

**Known limitations** (deliberate for now, not oversights):
- *Leaving a church doesn't cut a live subscription.* Access is checked on `SUBSCRIBE` and on every `SEND`, so a member who leaves can no longer post or reconnect, but a socket they already have open keeps *receiving* until it disconnects.
- *No rate limiting* on sending, and no edit/delete/report on messages — moderation belongs with the Vetting work (`Report`, not yet built).
- *Single-node broker.* The in-memory simple broker only reaches clients connected to the same server process. Running more than one API instance needs an external broker (RabbitMQ/ActiveMQ via STOMP relay) first.
- *Sender identity is a display name* (email local part). Two people with the same local part at different domains are indistinguishable in the UI, and the client's "is this mine?" styling compares by that name.

## Client conventions

**The Angular app is zoneless — write state as signals.** There is no `zone.js`, so Angular does *not* automatically re-render after an HTTP response or timer. A component that does `this.loading = false` on a plain field inside a `subscribe` callback is never repainted: the page spins forever, a failed login shows no error, an edit form stays empty. This was the real cause of the "infinite loading" bug on the church profile and edit pages (the API was answering fine in 50 ms). Anything assigned *asynchronously* and read by the template must be a `signal` (or a plain-looking accessor over one — see `church-profile.component.ts`). Plain fields are fine only for values changed by user events (which do schedule a render) or set synchronously before first render.

The tests are zoneless too, and they can catch this — but only if they don't force a render. Specs such as `church-profile.component.spec.ts` and `auth.component.spec.ts` deliberately `await fixture.whenStable()` (Angular's own scheduled render) instead of calling `fixture.detectChanges()` after the response, and let the triggering event's render finish *before* the response arrives, as in a browser. Calling `detectChanges()` by hand would hide the bug.

**Custom elements are `display: inline` by default.** Vertical margins from a parent's `space-y-*` don't apply to a child component's host element, so cards would touch. Give components that render a card `host: { class: 'block' }`.

**Dev proxy rules match path *prefixes*.** `apps/client/proxy.conf.json` must say `"/r/"`, not `"/r"` — the latter also swallowed `/register` (a deep link or refresh on it got the API's 401 instead of the app). Any rule added there needs the same care.

## Database

**Decision: PostgreSQL**, specifically **Neon** (serverless Postgres) for hosted environments, with a local Docker Postgres for development — already the direction this project was headed (the datasource was pointed at a Neon instance before this pass; H2 was only ever used for tests).

Compared against the alternatives:

- **PostgreSQL (Neon) — chosen.** The domain is relational and getting more so: `Church`/`ServiceTime` today, with `User`, `Claim`, `ChurchStaff`, `Member`, `Event`, `Article`, `Survey`/`Question`/`Response`, and `Report` planned (see DATA_MODEL.md) — lots of foreign keys and joins, which is exactly what Postgres is for. Neon specifically scales to zero between requests, which matters because the Lambda scraper (Prompt 16/17) only writes in short bursts on a schedule and shouldn't require a permanently-warm, always-billed instance. Postgres also has first-class full-text search (`tsvector`), which will be needed for church name/description search and later for article search — no bolt-on service required.
- **MySQL — rejected.** Comparable relational fit, but no meaningful advantage over Postgres here, and Neon (or RDS/Aurora) has better serverless/scale-to-zero options in the Postgres ecosystem than are readily available for MySQL.
- **DynamoDB — rejected.** The access patterns aren't key-value/single-table shaped — profile pages need joined data (church + service times + staff + events it participates in), claims/reports need admin-side scans and filtering that DynamoDB makes awkward, and the planned full-text article/church search would need a bolted-on OpenSearch domain that Postgres gets for free. DynamoDB would only pay off if the write pattern were extremely high-throughput single-item lookups, which this isn't.

**Lambda connection pooling:** direct JDBC connections from Lambda are the classic connection-storm failure mode. Since the scraper never writes to the database directly (it POSTs to the server's ingestion endpoint — see SCRAPER.md), the server is the only long-lived process holding a connection pool, and standard HikariCP pooling is sufficient. If a future Lambda ever needs direct DB access, put RDS Proxy (or Neon's built-in PgBouncer pooler endpoint) in front of it rather than opening raw connections per invocation.

**Secrets:** database credentials are supplied via environment variables (`DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`), never committed. See [COMMANDS.md](COMMANDS.md) for local setup.

**Schema management: Flyway**, not Hibernate `ddl-auto` (`server/src/main/resources/db/migration`). `ddl-auto=update` was fine for early prototyping but can't express destructive changes safely and can't run `CREATE EXTENSION` at all — both matter now that the schema needs real migrations (PostGIS below) at real scale. Hibernate is set to `ddl-auto=validate`: it checks the entities against whatever Flyway created, and never mutates schema itself. Tests run against a real Testcontainers-managed Postgres (`AbstractIntegrationTest`, image `postgis/postgis:16-3.4`) rather than H2, since PostGIS/pg_trgm have no H2 equivalent — this makes Docker a hard requirement for `mvn test` (see COMMANDS.md).

**Search infra at 250k+ churches: Postgres-native**, not a dedicated search service — `pg_trgm` (fuzzy trigram text search on `churches.name`/`city`, GIN-indexed) and **PostGIS** (spatial queries via a `geography(Point, 4326)` column on `churches`, GiST-indexed, replacing the old in-application Haversine sort — see "Geocoding" above) both come free with Postgres, and the access patterns (fuzzy name search, "nearest N churches") don't need Elasticsearch/OpenSearch's inverted-index machinery to perform well at this scale. Both extensions are enabled in `V2__enable_postgis_and_pg_trgm.sql`.
>
> ⚠️ **Risk, not yet confirmed:** whether Neon's plan actually supports `CREATE EXTENSION postgis` (`pg_trgm` ships in every Postgres build's contrib and is not a concern). If it doesn't, `V2` fails loudly on startup against the real Neon instance — intentional, so the gap surfaces immediately rather than the app silently falling back to something slower. **Fallback if PostGIS isn't available:** a plain bounding-box pre-filter (`WHERE latitude BETWEEN ... AND longitude BETWEEN ...`) backed by a composite B-tree index on `churches(latitude, longitude)`, in place of true PostGIS KNN — meaningfully less precise (a box, not a radius) and unable to order strictly by distance without an in-app sort of the (much smaller, box-filtered) result set. Local dev sidesteps this entirely: `docker-compose.yml` runs `postgis/postgis:16-3.4` instead of `postgres:16-alpine` specifically so PostGIS is always available locally regardless of what Neon ends up supporting.

> ⚠️ A previous commit hardcoded a live Neon username/password directly in `server/src/main/resources/application.properties`. That credential must be rotated in the Neon console — moving it to an environment variable in this pass stops new leaks but does not undo the exposure of the old one.

## Object Storage

**Decision (Prompts 8–9): Amazon S3**, uploaded to directly by the browser via short-lived presigned PUT URLs — files never pass through the Spring server, so upload size/bandwidth doesn't touch API memory. `S3PresignService` (`server/src/main/java/com/everychurch/server/service`) is the one place that builds S3 URLs (both the presigned upload URL and the public object URL), shared by `ChurchImageService` (`church-images/{churchId}/{uuid}.{ext}`, ~5 minute expiry) and `SermonService` (`church-sermons/{churchId}/{uuid}.{ext}`, ~15 minute expiry — audio/video files are bigger and slower to upload).

**Flow:** `POST /api/churches/{id}/images/upload-url` or `.../sermons/upload-url` (same `EDIT_PROFILE`/`MANAGE_SERMONS`/Platform-Admin gate as the rest of that resource) returns `{uploadUrl, objectKey, publicUrl}`. The client `PUT`s the file body straight to `uploadUrl` with a matching `Content-Type`, then calls the existing `POST .../images` or `.../sermons` with `publicUrl` — that row-creation step is unchanged from before uploads existed.

**Configuration:** `S3_BUCKET_NAME` (`aws.s3.bucket-name`) and `AWS_REGION` (`aws.region`, default `us-east-1`). AWS credentials come from the SDK's standard default chain (`AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` env vars, or an instance profile in real deployments) — no custom credential plumbing. Left unset in dev/test on purpose: `S3PresignService` throws a clear `IllegalStateException` (mapped to 503) rather than crashing the app when the bucket isn't configured, the same degrade-gracefully pattern as `GoogleGeocodingService`.

**Bucket setup (provision manually — not something this codebase can do for you):** public-read bucket policy (or a CDN like CloudFront later, not needed now) so uploaded images/sermons are servable at their plain `https://{bucket}.s3.{region}.amazonaws.com/{key}` URL, and CORS enabled for direct browser `PUT` from the Angular app's origin (`http://localhost:4200` in dev; the real client origin in production).

## Deployment / Hosting

- `apps/scraper` runs as a scheduled AWS Lambda (per README) — see [SCRAPER.md](SCRAPER.md) for its pipeline, which now includes real Places API discovery, dedup, enrichment, and submission to the server (`apps/scraper/scraper/pipeline.py`).
- `server` and `apps/client` hosting targets are not yet decided.

> TBD — flesh out once real deployment (not just local dev) is in scope.
