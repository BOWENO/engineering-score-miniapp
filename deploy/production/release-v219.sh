#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
APP=/home/ubuntu/apps/engineering-score
SOURCE=server/src/main/java/com/acme/performance/auth/service/AdminAuthService.java
EXPECTED_OLD=9c1a63faaf6b8002be82a279a3a6ce9f524741fddd57a255ab4afd087ba418a9
EXPECTED_NEW=aba27078b9430e4f2e6c6e04acee92d44911928732dd0bab0e38bed92a7b4807
test "$(sha256sum "$APP/$SOURCE" | cut -d' ' -f1)" = "$EXPECTED_OLD"
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
BACKUP=/var/backups/engineering-score/v219-$STAMP
mkdir -p "$BACKUP"
docker exec engineering-score-postgres-1 sh -c 'exec pg_dump -U "$POSTGRES_USER" -d engineering_score -Fc' > "$BACKUP/postgres.dump"
test -s "$BACKUP/postgres.dump"
docker exec -i engineering-score-postgres-1 pg_restore --list < "$BACKUP/postgres.dump" > "$BACKUP/postgres-list.txt"
tar -czf "$BACKUP/server-source.tar.gz" -C "$APP" server/src
readlink -f /var/www/engineering-score-admin/current > "$BACKUP/web-previous.txt"
PREVIOUS_IMAGE=$(docker inspect --format '{{.Image}}' engineering-score-server-1)
docker tag "$PREVIOUS_IMAGE" "engineering-score-server:before-v219-$STAMP"
printf '%s\n' "$PREVIOUS_IMAGE" > "$BACKUP/server-image.txt"
sha256sum "$BACKUP/postgres.dump" "$BACKUP/server-source.tar.gz" > "$BACKUP/SHA256SUMS"
echo "Backup complete: $BACKUP"
tar -xzf /home/ubuntu/release-v219.tar.gz -C "$APP"
test "$(sha256sum "$APP/$SOURCE" | cut -d' ' -f1)" = "$EXPECTED_NEW"
cd "$APP/deploy/production"
docker compose -f docker-compose.yml -f docker-compose.host.yml build server
docker compose -f docker-compose.yml -f docker-compose.host.yml up -d --no-deps server
for attempt in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:8080/actuator/health/readiness > /dev/null 2>&1; then break; fi
  sleep 2
done
curl -fsS http://127.0.0.1:8080/actuator/health/readiness
WEB=/var/www/engineering-score-admin/releases/v219-$STAMP
install -d -m 755 "$WEB"
tar -xzf /home/ubuntu/web-v219.tar.gz -C "$WEB"
chmod -R a+rX "$WEB"
test -f "$WEB/index.html"
test ! -e /var/www/engineering-score-admin/current-next
ln -s "$WEB" /var/www/engineering-score-admin/current-next
mv -Tf /var/www/engineering-score-admin/current-next /var/www/engineering-score-admin/current
echo "Deployed web: $WEB"
docker compose -f docker-compose.yml -f docker-compose.host.yml ps server
sha256sum "$APP/$SOURCE"
