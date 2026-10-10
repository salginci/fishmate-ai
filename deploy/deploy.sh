#!/usr/bin/env bash
# Deploy FishMate to the tr-vds host.
#
# Idempotent: safe to re-run. Only touches /opt/apps/fishmate and the two
# containers it owns; every other deployment on the host is left alone.
set -euo pipefail

HOST="${FISHMATE_SSH_HOST:-tr-vds}"
REMOTE_DIR="/opt/apps/fishmate"
DOMAIN="fisherman.salginci.com"

say() { printf "\033[36m▸ %s\033[0m\n" "$1"; }

say "Syncing source to ${HOST}:${REMOTE_DIR}/repo"
ssh "$HOST" "mkdir -p ${REMOTE_DIR}/repo"
rsync -az --delete \
  --exclude '.venv' --exclude '__pycache__' --exclude '.pytest_cache' \
  --exclude '.ruff_cache' --exclude '.mypy_cache' --exclude '*.db' \
  --exclude '.coverage' --exclude 'htmlcov' --exclude '.env' \
  -e "ssh" \
  ./backend/ "${HOST}:${REMOTE_DIR}/repo/backend/"

say "Uploading compose file"
scp -q deploy/docker-compose.prod.yml "${HOST}:${REMOTE_DIR}/docker-compose.yml"

say "Building image (this runs on the server)"
ssh "$HOST" "cd ${REMOTE_DIR} && docker compose build --pull api"

say "Starting database"
ssh "$HOST" "cd ${REMOTE_DIR} && docker compose up -d db"

say "Applying migrations"
ssh "$HOST" "cd ${REMOTE_DIR} && docker compose run --rm --entrypoint alembic api upgrade head"

say "Starting API"
ssh "$HOST" "cd ${REMOTE_DIR} && docker compose up -d api"

say "Waiting for health"
ssh "$HOST" "for i in \$(seq 1 30); do
  s=\$(docker inspect --format='{{.State.Health.Status}}' fishmate-api 2>/dev/null || echo none)
  [ \"\$s\" = healthy ] && { echo '  container healthy'; exit 0; }
  sleep 2
done; echo '  WARNING: never became healthy'; docker logs --tail 40 fishmate-api"

say "Verifying through the proxy"
for i in $(seq 1 20); do
  code=$(curl -s -o /dev/null -w '%{http_code}' "https://${DOMAIN}/api/v1/health" || echo 000)
  [ "$code" = "200" ] && { echo "  https://${DOMAIN}/api/v1/health -> 200"; break; }
  echo "  waiting for TLS/proxy (${code})…"; sleep 6
done
