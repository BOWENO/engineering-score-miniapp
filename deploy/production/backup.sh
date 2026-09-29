#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"
set -a
source ./.env
set +a

BACKUP_ROOT="${BACKUP_ROOT:-/var/backups/engineering-score}"
RETENTION_DAYS="${RETENTION_DAYS:-30}"
OBJECT_STORAGE_BUCKET="${OBJECT_STORAGE_BUCKET:-engineering-score-evidence}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
TARGET="${BACKUP_ROOT}/${STAMP}"
NETWORK="${COMPOSE_PROJECT_NAME:-engineering-score}_default"

mkdir -p "${TARGET}/minio"
docker compose exec -T postgres pg_dump \
  --username "${DB_USERNAME}" --dbname engineering_score \
  --format=custom --no-owner --no-acl > "${TARGET}/postgres.dump"

docker run --rm --network "${NETWORK}" \
  -e MC_HOST_storage="http://${OBJECT_STORAGE_ACCESS_KEY}:${OBJECT_STORAGE_SECRET_KEY}@minio:9000" \
  minio/mc:RELEASE.2025-07-21T05-28-08Z \
  mb --ignore-existing "storage/${OBJECT_STORAGE_BUCKET}"

docker run --rm --network "${NETWORK}" \
  -e MC_HOST_storage="http://${OBJECT_STORAGE_ACCESS_KEY}:${OBJECT_STORAGE_SECRET_KEY}@minio:9000" \
  -v "${TARGET}/minio:/backup" minio/mc:RELEASE.2025-07-21T05-28-08Z \
  mirror --overwrite "storage/${OBJECT_STORAGE_BUCKET}" "/backup/${OBJECT_STORAGE_BUCKET}"

sha256sum "${TARGET}/postgres.dump" > "${TARGET}/SHA256SUMS"
find "${BACKUP_ROOT}" -mindepth 1 -maxdepth 1 -type d -mtime "+${RETENTION_DAYS}" -print -exec rm -rf -- {} +
echo "Backup completed: ${TARGET}"
