#!/usr/bin/env bash
# Alternate-port variant of dev.sh: client on :4201 proxying to a second API
# instance on :8081 (see apps/client/proxy.conf.alt.json / the client's
# alt-ports serve configuration), so a second stack can run alongside the one
# `npm run dev` starts without port clashes. Both instances share the same
# Postgres container — this isn't a second database, just a second API process
# for e.g. testing two sessions against independently-restartable servers.
# `npm run dev:alt` runs this.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

if [ ! -f .env ]; then
  echo "warning: .env not found — copy .env.example to .env and fill in real values first" >&2
fi

docker compose up -d db

(cd server && SERVER_PORT=8081 ./mvnw -q spring-boot:run) &
SERVER_PID=$!
trap 'kill "$SERVER_PID" 2>/dev/null' EXIT

npx nx serve client --configuration=alt-ports
