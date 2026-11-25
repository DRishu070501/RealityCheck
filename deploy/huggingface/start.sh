#!/bin/bash
# Starts PostgreSQL, the backend and nginx in one container (Hugging Face Space). The database is created fresh
# in /tmp on every start and the demonstration is seeded into it, so a restart is also a reset.
set -euo pipefail

PGBIN=$(ls -d /usr/lib/postgresql/*/bin | sort -V | tail -1)
export PGDATA=/tmp/pgdata PGHOST=127.0.0.1 PGUSER=realitycheck
export PGPASSWORD=$(head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 32)

rm -rf "$PGDATA"
echo "$PGPASSWORD" > /tmp/pwfile
"$PGBIN/initdb" -D "$PGDATA" -U realitycheck --pwfile=/tmp/pwfile -A scram-sha-256 >/dev/null
rm -f /tmp/pwfile
"$PGBIN/pg_ctl" -D "$PGDATA" -l /tmp/postgres.log -w start \
  -o "-c listen_addresses=127.0.0.1 -c unix_socket_directories=/tmp -c shared_buffers=128MB"
"$PGBIN/createdb" realitycheck
echo "start: database ready"

export SPRING_PROFILES_ACTIVE=prod
export DB_URL=jdbc:postgresql://127.0.0.1:5432/realitycheck DB_USERNAME=realitycheck DB_PASSWORD="$PGPASSWORD"
export API_DOCS_ENABLED=false REALITYCHECK_DEMO_SEED=true REALITYCHECK_DEMO_RESET_ENABLED=false
# Hugging Face sets SPACE_HOST (<user>-<space>.hf.space); a local test passes its own origin.
export CORS_ALLOWED_ORIGINS="${CORS_ALLOWED_ORIGINS:-https://${SPACE_HOST:-localhost}}"
# The analyst: Claude only when the Space has an AI_API_KEY secret; otherwise the built-in analyst.
if [ -n "${AI_API_KEY:-}" ]; then export AI_PROVIDER="${AI_PROVIDER:-anthropic}"; else export AI_PROVIDER=template; fi
export AI_DAILY_BUDGET_USD="${AI_DAILY_BUDGET_USD:-2}" AI_REQUESTS_PER_MINUTE="${AI_REQUESTS_PER_MINUTE:-10}"
unset PGPASSWORD

java -jar /app/app.jar &
backend=$!

# Open the port only when the backend is ready, so a cold start shows "Starting" rather than a broken page.
for _ in $(seq 1 180); do
  kill -0 "$backend" 2>/dev/null || { echo "start: backend exited"; exit 1; }
  curl -fsS -o /dev/null http://127.0.0.1:8080/actuator/health/readiness && break
  sleep 1
done
echo "start: backend ready"

nginx -c /app/nginx.conf -g 'daemon off;' &
web=$!

# Stop the container if either process stops, so the Space restarts it.
wait -n "$backend" "$web"
exit 1
