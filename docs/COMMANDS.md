# Commands

## Setup

```sh
npm install                 # installs Nx, Angular, and JS tooling
cd apps/scraper && poetry install && cd ../..   # Python deps for the scraper
```

Copy `.env.example` to `.env` and fill in real values before running the server against a real database (see "Database" below). Never commit `.env`.

## Production URLs

| What | URL | Hosted on |
|---|---|---|
| Website (Angular client) | https://everychurch.co | Vercel |
| API server (Spring Boot) | https://everychurch-server-274948593192.us-east1.run.app | Google Cloud Run (us-east1) |

The website and API are on **different hosts**. `vercel.json` rewrites `everychurch.co/api/*` and `/ws/*` to the Cloud Run URL, so the browser only ever talks to everychurch.co. For scripts, **set `SERVER_BASE_URL` to the Cloud Run URL**, not everychurch.co. The scraper and import scripts make slow requests (paging the whole directory, 100-row bulk batches), and the extra hop through Vercel's proxy can time those out.

Quick health check (public, read-only): `curl https://everychurch-server-274948593192.us-east1.run.app/api/import-status`

If the Cloud Run URL ever changes, update it here, in `vercel.json`, and in [ARCHITECTURE.md](ARCHITECTURE.md) "Deployment / Hosting".

### Live demo checklist

`npm run check:prod` hits the API directly and through everychurch.co, wakes a cold instance, and prints the startup error if the API is down.

- **The day before:** run `npm run check:prod`. After that, freeze: no server deploys, secret changes or migrations until the demo is over.
- **About an hour before:** keep one instance always running so visitors never hit a cold start, and nothing restarts mid-demo onto a bad config:
  ```sh
  gcloud run services update everychurch-server --region us-east1 --min-instances 1
  ```
  This creates a new revision. If that fails, the old one keeps serving and you find out now, not on stage.
- **15 minutes before:** run `npm run check:prod` again. In the browser you'll present from, click through the exact demo path and log in fresh (sessions expire after 24h).
- **Have a fallback:** a screen recording of the demo path, in case the venue Wi-Fi or a third-party service fails.
- **After the demo:** `--min-instances 0` to stop paying for the idle instance.

### Production is down ("We couldn't load churches")

Cloud Run scales to zero, so a config problem shows up only on the **next cold start**, which can be days after the change that caused it. Check `gcloud run revisions list --service everychurch-server --region us-east1` and the error from `npm run check:prod`.

- `password authentication failed for user 'neondb_owner'`: check the variable name first. The server reads `DATABASE_PASSWORD`, falling back to `DB_PASSWORD`. Until 2026-10-12 it read *only* `DB_PASSWORD` while Cloud Run set only `DATABASE_PASSWORD`, so any fresh build connected with no password. If the name is right, the password itself is wrong: reset it in the Neon console (Roles → neondb_owner), then:
  ```sh
  printf %s 'NEW_PASSWORD' | gcloud secrets versions add everychurch-db-password --data-file=-
  gcloud run services update everychurch-server --region us-east1 \
    --update-secrets DATABASE_PASSWORD=everychurch-db-password:<N>,DB_PASSWORD=everychurch-db-password:<N>
  ```
  Use `printf %s`, not `echo`. A trailing newline becomes part of the password.
- **Pin the secret to a numbered version, never `latest`.** With `latest`, adding a secret version silently changes what the *running* service picks up on its next cold start. A pinned version only changes when you deploy, and a bad deploy fails then and there while traffic stays on the old revision.

## Dev servers

```sh
npm run dev               # everything at once: docker db + server (:8080) + client (:4200)

# or individually
npx nx serve server      # Spring Boot API → http://localhost:8080
npx nx serve client      # Angular dev server → http://localhost:4200, proxies /api to :8080 (apps/client/proxy.conf.json)
npx nx run-many --target=serve --all   # same as npm run dev, minus the docker/`.env` check
```

`npm run dev` (`scripts/dev.sh`) starts the local Postgres container, then runs `nx run-many --target=serve --all`. Its `Ctrl+C` stops both the client and server together.

`npm run dev:alt` (`scripts/dev-alt.sh`) starts a second, independent API instance on `:8081` (`SERVER_PORT=8081`, plain `mvnw spring-boot:run` rather than the `demo` nx target, so it can run alongside the main `npm run dev` server without a port clash) plus the client's `alt-ports` configuration (`:4201`, proxying to `:8081` via `apps/client/proxy.conf.alt.json`). Both instances share the one Postgres container — this gives you two independently-restartable API processes, not a second database, useful for e.g. testing against a server you're about to restart without taking down the other session.

**Note for Spring Boot backend changes specifically:** the `demo`/`server` project has no devtools configured, so neither `npm run dev` nor a plain `npx nx serve server` picks up structural Java changes (new endpoints, changed method signatures) automatically the way the Angular dev server hot-reloads — restart it after backend edits.

## Testing

```sh
npx nx test client        # Angular unit tests (Jest)
npx nx e2e client-e2e      # Playwright e2e
npx nx test server         # Spring Boot tests (Maven, via @nxrocks/nx-spring-boot)
npx nx test scraper        # pytest, via Poetry
npx nx run-many --target=test --all
```

## Database

Local development uses Postgres via Docker Compose (see [ARCHITECTURE.md](ARCHITECTURE.md) for why Postgres/Neon was chosen):

```sh
docker compose up -d db     # starts local Postgres on :5432
docker compose down         # stops it (add -v to also drop the volume/data)
```

The server reads `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` from the environment (see `.env.example`), defaulting to the local Docker Postgres if unset. Point those at a Neon connection string instead to run against a hosted database.

Schema is managed via **Flyway** (`server/src/main/resources/db/migration`, `V1__...`, `V2__...`, ...) — Hibernate is set to `ddl-auto=validate` and never mutates schema itself. Add a new migration rather than changing an existing one once it's been run anywhere.

> ⚠️ **Migration version numbers are a collision point.** Every new migration takes "the next sequential version number." If you're running multiple prompts/agents against this repo concurrently, check `db/migration/` for the actual highest `V*` present immediately before writing a new one — don't assume, and don't let two in-flight migrations both claim the same number.

**Docker is now a hard requirement for `npx nx test server` / `mvn test`**, not just local dev: tests run against a real Testcontainers-managed Postgres+PostGIS (`AbstractIntegrationTest`, image `postgis/postgis:16-3.4`) rather than H2, since PostGIS/pg_trgm have no H2 equivalent. Make sure Docker is running before running the server test suite.

## Seeding

- `DataLoader` seeds 4 sample Chicago-area churches automatically on server startup, but only if the `churches` table is empty (`repo.count() > 0` short-circuits it) — so it never overwrites real data.
- `AdminUserSeeder` seeds one Platform Admin (`ADMIN_EMAIL`/`ADMIN_PASSWORD`, defaults in `.env.example`) on startup if no `PLATFORM_ADMIN` user exists yet — nothing else can create the first one, since `/api/admin/**` requires that role and there's no public path to it. Log in as this account to generate volunteer invites (`/admin/volunteer-invites`) or review submissions/claims (`/admin/submissions`).

## Environment variables

See `.env.example` for the full list with defaults. Sensible dev defaults mean the app runs out of the box with `docker compose up -d db`, but several features degrade gracefully rather than erroring when their key is unset:

| Variable | Feature | If unset |
|---|---|---|
| `GOOGLE_GEOCODING_API_KEY` | Church lat/lng on save (`GoogleGeocodingService`) | Churches save without coordinates; `GET /api/churches/nearest` just won't find them |
| `GOOGLE_PLACES_API_KEY`, `SCRAPER_API_KEY`, `SERVER_BASE_URL`, `SCRAPER_SEARCH_RADIUS_M` | `apps/scraper` discovery pipeline (see [SCRAPER.md](SCRAPER.md)) | Not read by the server; required to actually run `scraper.pipeline.run()` |
| `CLIENT_BASE_URL` | Volunteer field kit's `/r/nearest` redirect target | Falls back to `http://localhost:4200` |
| `JWT_SECRET`, `ADMIN_EMAIL`/`ADMIN_PASSWORD` | Auth (see ARCHITECTURE.md) | Insecure dev-only defaults are used — **never leave these unset outside local dev** |
