# EveryChurch

A platform for discovering, reviewing, and managing churches. Visitors get a standardized, clean profile page for any church; churches can claim their listing and use the platform as an internal management system. Church data is continuously populated via an automated scraping pipeline and a network of volunteers.

## What it does

**For visitors:**
- Search and browse churches with a standardized profile page — service times, location, contact info, denomination, image gallery, sermons, and multi-church events
- Register/log in; find the nearest church by location (`GET /api/churches/nearest`) via the volunteer field kit's printable QR flyer

**For churches:**
- Claim ownership of an unclaimed listing (reviewed and approved by a Platform Admin, not automatic)
- Manage staff with fine-grained permissions (edit profile, manage sermons, manage events, manage staff) once claimed
- Upload sermons, manage an image gallery, and co-host events with other churches

**For volunteers:**
- Onboard via an admin-issued invite code (not self-service registration — see `docs/ARCHITECTURE.md`)
- Submit new churches or propose edits to existing ones; every submission sits in an admin-reviewed verification queue before it goes live

**Data pipeline:**
- `apps/scraper` (Python, AWS Lambda) tiles the continental US and discovers churches via the Google Places API, enriches them from their own websites, dedupes against existing records, and submits into the same verification queue volunteers use
- Address-based geocoding (Google Geocoding API) backs the nearest-church lookup

Full feature-by-feature status (including what's still unbuilt — Faith Formation, Vetting/moderation, member networking, promotion) lives in [docs/DATA_MODEL.md](docs/DATA_MODEL.md) and [docs/PRODUCT_SCOPE.md](docs/PRODUCT_SCOPE.md).

## Architecture

This is an Nx monorepo with three apps:

| App | Tech | Purpose |
|-----|------|---------|
| `apps/client` | Angular 21, Tailwind CSS | Frontend SPA |
| `server` | Spring Boot 4 (Java 21) | REST API backend |
| `apps/scraper` | Python, AWS Lambda | Data ingestion pipeline |

```
everychurch/
├── apps/
│   ├── client/          # Angular frontend
│   ├── client-e2e/      # Playwright end-to-end tests
│   └── scraper/         # Python scraper (AWS Lambda)
├── server/              # Spring Boot API
└── docs/                # Project documentation
```

## Getting started

### Prerequisites

- Node.js 20+
- Java 21
- Python 3.8–3.10 (managed via pyenv, see `.python-version`)
- Poetry (Python dependency management)

### Install dependencies

```sh
npm install
docker compose up -d db     # local Postgres — see docs/COMMANDS.md
cp .env.example .env        # then fill in real values; never commit .env
```

### Run the full stack

```sh
# Run all services concurrently
npx nx run-many --target=serve --all

# Or run individually
npx nx serve client      # Angular dev server → http://localhost:4200
npx nx serve server      # Spring Boot API → http://localhost:8080
```

### Run tests

```sh
npx nx test client
npx nx e2e client-e2e
npx nx test scraper      # Python tests via pytest
```

## Docs

- [Commands reference](docs/COMMANDS.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Data model](docs/DATA_MODEL.md)
- [Scraper / data pipeline](docs/SCRAPER.md)
- [Product scope — what's in/out, and why](docs/PRODUCT_SCOPE.md)
