#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
APP=/home/ubuntu/apps/engineering-score
cd /home/ubuntu
sha256sum -c release-v2112.sha256
cd "$APP"
# Refuse to overwrite a server that differs from the inspected source baseline.
while read -r expected file; do
  actual=$(sed 's/\r$//' "$file" | sha256sum | cut -d' ' -f1)
  test "$actual" = "$expected" || { echo "SOURCE_BASELINE_MISMATCH $file"; exit 1; }
  echo "SOURCE_BASELINE_OK $file"
done < /home/ubuntu/release-v2112-baseline.sha256
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
BACKUP=/var/backups/engineering-score/v2112-$STAMP
mkdir -p "$BACKUP"
docker exec engineering-score-postgres-1 sh -c 'exec pg_dump -U "$POSTGRES_USER" -d engineering_score -Fc' > "$BACKUP/postgres.dump"
test -s "$BACKUP/postgres.dump"
docker exec -i engineering-score-postgres-1 pg_restore --list < "$BACKUP/postgres.dump" > "$BACKUP/postgres-list.txt"
tar -czf "$BACKUP/source.tar.gz" server/src/main admin-web/src
PREVIOUS_WEB=$(readlink -f /var/www/engineering-score-admin/current)
test -f "$PREVIOUS_WEB/index.html"
printf '%s\n' "$PREVIOUS_WEB" > "$BACKUP/web-previous.txt"
PREVIOUS_IMAGE=$(docker inspect --format '{{.Image}}' engineering-score-server-1)
docker tag "$PREVIOUS_IMAGE" "engineering-score-server:before-v2112-$STAMP"
printf '%s\n' "$PREVIOUS_IMAGE" > "$BACKUP/server-image.txt"
sha256sum "$BACKUP/postgres.dump" "$BACKUP/source.tar.gz" > "$BACKUP/SHA256SUMS"
echo "BACKUP=$BACKUP"
rollback(){
  result=$?; trap - ERR
  tar -xzf "$BACKUP/source.tar.gz" -C "$APP"
  cd "$APP/deploy/production"
  docker tag "$PREVIOUS_IMAGE" engineering-score-server:latest
  docker compose -f docker-compose.yml -f docker-compose.host.yml up -d --no-deps --no-build server
  ln -s "$PREVIOUS_WEB" /var/www/engineering-score-admin/rollback-v2112-$STAMP
  mv -Tf /var/www/engineering-score-admin/rollback-v2112-$STAMP /var/www/engineering-score-admin/current
  echo "DEPLOY_FAILED_ROLLED_BACK BACKUP=$BACKUP"; exit "$result"
}
trap rollback ERR
tar -xzf /home/ubuntu/release-v2112.tar.gz -C "$APP"
cd "$APP/deploy/production"
docker compose -f docker-compose.yml -f docker-compose.host.yml build server
docker compose -f docker-compose.yml -f docker-compose.host.yml up -d --no-deps --no-build server
for attempt in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:8080/actuator/health/readiness >/dev/null 2>&1; then break; fi
  sleep 2
done
curl -fsS http://127.0.0.1:8080/actuator/health/readiness
WEB=/var/www/engineering-score-admin/releases/v2112-$STAMP
install -d -m 755 "$WEB"
tar -xzf /home/ubuntu/web-v2112.tar.gz -C "$WEB"
chmod -R a+rX "$WEB"
test -f "$WEB/index.html"
ln -s "$WEB" /var/www/engineering-score-admin/current-v2112-$STAMP
mv -Tf /var/www/engineering-score-admin/current-v2112-$STAMP /var/www/engineering-score-admin/current
curl -fsS https://www.testengineering.cloud/api/health
curl -fsS https://www.testengineering.cloud/ | cmp - "$WEB/index.html"
echo "DEPLOY_SUCCESS WEB=$WEB BACKUP=$BACKUP"
