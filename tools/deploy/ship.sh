#!/usr/bin/env bash
# Builds the backend and frontend images here and runs them on the public server (spec 11, task 15).
#
#   tools/deploy/ship.sh                       # build and save local/deploy/realitycheck-images-<platform>.tar.gz
#   tools/deploy/ship.sh ubuntu@203.0.113.10   # ... then copy to the server, load and (re)start the stack
#
# PLATFORM defaults to linux/arm64 (Oracle's Ampere servers). The build stages run on this machine's own CPU and
# only the runtime stages are per platform, so no emulation is needed. The server must already have Docker and
# ~/realitycheck/deploy/public.env (see docs/deploy.md); the env file never leaves the server.
set -euo pipefail

PLATFORM="${PLATFORM:-linux/arm64}"
TAG="${PLATFORM##*/}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/local/deploy"
ARCHIVE="$OUT/realitycheck-images-$TAG.tar.gz"
mkdir -p "$OUT"

for svc in backend frontend; do
  echo "== building $svc for $PLATFORM"
  docker buildx build --platform "$PLATFORM" -t "realitycheck-$svc:$TAG" --load "$ROOT/$svc"
done

echo "== saving $ARCHIVE"
docker save "realitycheck-backend:$TAG" "realitycheck-frontend:$TAG" | gzip -1 > "$ARCHIVE"
ls -lh "$ARCHIVE"

[ $# -eq 0 ] && exit 0
SERVER="$1"
DIR=realitycheck
COMPOSE="docker compose -f docker-compose.yml -f deploy/docker-compose.public.yml -f deploy/docker-compose.prebuilt.yml --env-file deploy/public.env"

echo "== copying to $SERVER:~/$DIR"
ssh "$SERVER" "mkdir -p $DIR/deploy && test -f $DIR/deploy/public.env" \
  || { echo "Create ~/$DIR/deploy/public.env on the server first (from deploy/public.env.example)." >&2; exit 1; }
scp "$ROOT/docker-compose.yml" "$SERVER:$DIR/"
scp "$ROOT/deploy/docker-compose.public.yml" "$ROOT/deploy/docker-compose.prebuilt.yml" "$ROOT/deploy/Caddyfile" \
    "$ROOT/deploy/public.env.example" "$SERVER:$DIR/deploy/"
scp "$ARCHIVE" "$SERVER:$DIR/images.tar.gz"

echo "== loading and starting on $SERVER"
ssh "$SERVER" "cd $DIR && gunzip -c images.tar.gz | docker load && rm images.tar.gz && IMAGE_TAG=$TAG $COMPOSE up -d --wait && $COMPOSE ps"
