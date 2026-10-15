#!/usr/bin/env bash
# Pre-demo smoke check: hits the real API both directly (Cloud Run) and through
# the website's /api proxy (Vercel). A cold start can take ~10–20s, so this
# also warms the instance. Exits non-zero if either is down.
# `npm run check:prod` runs this. See docs/COMMANDS.md "Production is down".
set -uo pipefail

API=https://everychurch-server-274948593192.us-east1.run.app
SITE=https://everychurch.co
fail=0

for url in "$API/api/churches?size=1" "$SITE/api/churches?size=1"; do
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 60 "$url")
  if [ "$code" = 200 ]; then
    echo "OK    $code  $url"
  else
    echo "FAIL  $code  $url" >&2
    fail=1
  fi
done

if [ "$fail" = 1 ]; then
  echo >&2
  echo "Production API is unhealthy. Most recent startup error:" >&2
  gcloud logging read 'resource.type="cloud_run_revision" AND resource.labels.service_name="everychurch-server" AND textPayload:"Caused by"' \
    --limit 1 --freshness=1d --format='value(textPayload)' 2>/dev/null | grep -o 'Caused by.*' | tail -1 >&2
fi
exit $fail
