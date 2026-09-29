#!/usr/bin/env bash
set -Eeuo pipefail

if [[ $# -ne 1 || ! -d "$1" ]]; then
  echo "Usage: $0 /absolute/path/to/backup-directory" >&2
  exit 2
fi

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SOURCE="$(cd -- "$1" && pwd)"
test -f "${SOURCE}/postgres.dump"
cd "${SCRIPT_DIR}"
set -a
source ./.env
set +a
OBJECT_STORAGE_BUCKET="${OBJECT_STORAGE_BUCKET:-engineering-score-evidence}"
NETWORK="${COMPOSE_PROJECT_NAME:-engineering-score}_default"

echo "Stopping the application and replacing the database from ${SOURCE}."
docker compose stop server
docker compose exec -T postgres dropdb --username "${DB_USERNAME}" --if-exists engineering_score
docker compose exec -T postgres createdb --username "${DB_USERNAME}" engineering_score
docker compose exec -T postgres pg_restore \
  --username "${DB_USERNAME}" --dbname engineering_score --no-owner --no-acl < "${SOURCE}/postgres.dump"

if [[ -d "${SOURCE}/minio/${OBJECT_STORAGE_BUCKET}" ]]; then
  docker run --rm --network "${NETWORK}" \
    -e MC_HOST_storage="http://${OBJECT_STORAGE_ACCESS_KEY}:${OBJECT_STORAGE_SECRET_KEY}@minio:9000" \
    -v "${SOURCE}/minio:/backup:ro" minio/mc:RELEASE.2025-07-21T05-28-08Z \
    mirror --overwrite "/backup/${OBJECT_STORAGE_BUCKET}" "storage/${OBJECT_STORAGE_BUCKET}"
fi

docker compose start server
echo "Restore completed from: ${SOURCE}"
