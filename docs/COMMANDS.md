# Commands

## Setup

```sh
npm install                 # installs Nx, Angular, and JS tooling
cd apps/scraper && poetry install && cd ../..   # Python deps for the scraper
```

Copy `.env.example` to `.env` and fill in real values before running the server against a real database (see "Database" below). Never commit `.env`.

## Dev servers

```sh
npx nx serve server      # Spring Boot API → http://localhost:8080
npx nx serve client      # Angular dev server → http://localhost:4200, proxies /api to :8080 (apps/client/proxy.conf.json)

# or run everything at once
npx nx run-many --target=serve --all
```

> `npm run dev` / `npm run dev:alt` are defined in `package.json` (pointing at `scripts/dev.sh` / `scripts/dev-alt.sh`) but those script files don't exist yet — use the `nx serve` commands above until that's fixed. `apps/client/proxy.conf.alt.json` (proxying to `:8081`) suggests the intent was to let the client point at a second server instance; `dev:alt` presumably would serve the client against that proxy config via `--proxy-config apps/client/proxy.conf.alt.json`.

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
