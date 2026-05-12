# EveryChurch

A platform for discovering, reviewing, and managing churches. Users get a standardized, clean view of any church with aggregated data from Google Maps, Yelp, and other external sources. Churches can claim their listing and use the platform as an internal management system. Church data is continuously populated via an automated scraping pipeline.

## What it does

**For visitors:**
- Search and browse churches with a standardized, modern profile page
- See aggregated reviews and ratings pulled from Google Maps, Yelp, and other sources
- View service times, location, contact info, denomination, and other key details

**For churches:**
- Claim ownership of a listing to unlock management features
- Manage staff/employees
- Upload and store sermons
- Edit profile information

**Data pipeline:**
- Automated Google Maps traversal discovers churches
- Web scraping populates and keeps church data current
- Runs as an AWS Lambda worker (Python)

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
- [Scraper / data pipeline](docs/SCRAPER.md)
