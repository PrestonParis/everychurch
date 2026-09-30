#!/usr/bin/env bash
# Backgrounded from dev.sh: waits for the API to come up, and if the directory
# looks freshly-empty (a cold-start environment that's never had the real Kaggle
# dataset loaded — see docs/SCRAPER.md "Bulk import"), kicks off the existing,
# resumable/idempotent bulk_import.py automatically. A re-run against an
# already-populated DB just finds ~0 new rows via its own dedup-against-server
# check and exits fast, so it's always safe to attempt this.
#
# Never fails npm run dev: exits quietly (not an error) if BULK_IMPORT_API_KEY
# isn't configured, Poetry isn't installed, or the API never comes up — Kaggle
# network access and a Python toolchain aren't guaranteed in every dev environment.
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

EMPTY_THRESHOLD=50
LOG_FILE=/tmp/everychurch-bulk-import.log

if [ -f .env ]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi

[ -z "${BULK_IMPORT_API_KEY:-}" ] && exit 0
command -v poetry >/dev/null 2>&1 || exit 0

status=""
for _ in $(seq 1 60); do
  status=$(curl -s -f http://localhost:8080/api/import-status 2>/dev/null) && break
  sleep 2
done
[ -z "$status" ] && exit 0

count=$(echo "$status" | python3 -c "import json,sys; print(json.load(sys.stdin).get('churchCount', 0))" 2>/dev/null || echo 0)
if [ "$count" -ge "$EMPTY_THRESHOLD" ] 2>/dev/null; then
  exit 0
fi

echo "everychurch: directory looks empty (${count} churches) — starting the background bulk import (log: ${LOG_FILE})…"
(cd apps/scraper && poetry run python -m scraper.bulk_import) >>"$LOG_FILE" 2>&1 &
