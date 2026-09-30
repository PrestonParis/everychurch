#!/usr/bin/env bash
# Starts the full local stack in one command: Postgres (Docker), the Spring
# Boot API, and the Angular dev server — see README.md "Run the full stack".
# `npm run dev` runs this.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

if [ ! -f .env ]; then
  echo "warning: .env not found — copy .env.example to .env and fill in real values first" >&2
fi

docker compose up -d db

# Backgrounded, not part of the exec below — see maybe-bulk-import.sh for why
# this is always safe to attempt and never blocks/fails the rest of dev.sh.
bash scripts/maybe-bulk-import.sh &

# exec (not a backgrounded call) so Ctrl+C's SIGINT goes straight to this
# process and its children (client + server) instead of being caught by an
# intermediate shell that might not forward it, which would leave the dev
# servers running after the script "exits".
exec npx nx run-many --target=serve --all
