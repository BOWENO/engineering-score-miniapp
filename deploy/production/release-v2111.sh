#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
APP=/home/ubuntu/apps/engineering-score
COMMIT=$(cat /home/ubuntu/release-v2111.commit)
test ! -e "$APP/server/src/main/resources/db/migration/V24__score_live_revision.sql"
test ! -e "$APP/server/src/main/java/com/acme/performance/scoring/service/ScoreChangeController.java"
cd /home/ubuntu
sha256sum -c release-v2111.sha256
test "$(sha256sum "$APP/server/src/main/java/com/acme/performance/auth/service/AdminAuthService.java" | cut -d' ' -f1)" = 2fbc453687c21bcc27dd13c3dd0e9c6076fc6f5326ea70e56ee2bc43ab087791
docker exec engineering-score-postgres-1 sh -c 'psql -U "$POSTGRES_USER" -d engineering_score -Atc "select version,success from flyway_schema_history order by installed_rank desc limit 3"'
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
BACKUP=/var/backups/engineering-score/v2111-$STAMP
mkdir -p "$BACKUP"
docker exec engineering-score-postgres-1 sh -c 'exec pg_dump -U "$POSTGRES_USER" -d engineering_score -Fc' > "$BACKUP/postgres.dump"
test -s "$BACKUP/postgres.dump"
docker exec -i engineering-score-postgres-1 pg_restore --list < "$BACKUP/postgres.dump" > "$BACKUP/postgres-list.txt"
tar -czf "$BACKUP/server-source.tar.gz" -C "$APP" server/src server/pom.xml server/Dockerfile
readlink -f /var/www/engineering-score-admin/current > "$BACKUP/web-previous.txt"
PREVIOUS_IMAGE=$(docker inspect --format '{{.Image}}' engineering-score-server-1)
docker tag "$PREVIOUS_IMAGE" "engineering-score-server:before-v2111-$STAMP"
printf '%s\n' "$PREVIOUS_IMAGE" > "$BACKUP/server-image.txt"
sha256sum "$BACKUP/postgres.dump" "$BACKUP/server-source.tar.gz" > "$BACKUP/SHA256SUMS"
echo "BACKUP=$BACKUP"
SERVER_CHANGED=0
rollback() {
  result=$?
  trap - ERR
  echo "Deployment failed; restoring application version. Backup: $BACKUP"
  rm -f -- "$APP/server/src/main/resources/db/migration/V24__score_live_revision.sql" "$APP/server/src/main/java/com/acme/performance/scoring/service/ScoreChangeController.java"
  tar -xzf "$BACKUP/server-source.tar.gz" -C "$APP"
  if [ "$SERVER_CHANGED" = 1 ]; then
    docker tag "$PREVIOUS_IMAGE" engineering-score-server:latest
    docker compose -f docker-compose.yml -f docker-compose.host.yml up -d --no-deps --no-build server
  fi
  ln -s "$(cat "$BACKUP/web-previous.txt")" /var/www/engineering-score-admin/rollback-$STAMP
  mv -Tf /var/www/engineering-score-admin/rollback-$STAMP /var/www/engineering-score-admin/current
  echo 'Database backup retained; additive migration is not automatically reversed.'
  exit "$result"
}
cd "$APP/deploy/production"
trap rollback ERR
tar -xzf /home/ubuntu/release-v2111.tar.gz -C "$APP"
docker compose -f docker-compose.yml -f docker-compose.host.yml build server
SERVER_CHANGED=1
docker compose -f docker-compose.yml -f docker-compose.host.yml up -d --no-deps --no-build server
for attempt in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:8080/actuator/health/readiness > /dev/null 2>&1; then break; fi
  sleep 2
done
curl -fsS http://127.0.0.1:8080/actuator/health/readiness
docker exec engineering-score-postgres-1 sh -c 'psql -U "$POSTGRES_USER" -d engineering_score -Atc "select version,success from flyway_schema_history order by installed_rank desc limit 3"'
WEB=/var/www/engineering-score-admin/releases/v2111-$STAMP
install -d -m 755 "$WEB"
tar -xzf /home/ubuntu/web-v2111.tar.gz -C "$WEB"
chmod -R a+rX "$WEB"
test -f "$WEB/index.html"
ln -s "$WEB" /var/www/engineering-score-admin/current-$STAMP
mv -Tf /var/www/engineering-score-admin/current-$STAMP /var/www/engineering-score-admin/current
curl -fsS https://www.testengineering.cloud/api/health
curl -fsS https://www.testengineering.cloud/ | cmp - "$WEB/index.html"
echo "DEPLOY_SUCCESS WEB=$WEB BACKUP=$BACKUP COMMIT=$COMMIT"
