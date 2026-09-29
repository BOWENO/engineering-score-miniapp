#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
APP=/home/ubuntu/apps/engineering-score
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
BACKUP=/var/backups/engineering-score/v217-$STAMP
mkdir -p "$BACKUP"
docker exec engineering-score-postgres-1 sh -c 'exec pg_dump -U "$POSTGRES_USER" -d engineering_score -Fc' > "$BACKUP/postgres.dump"
test -s "$BACKUP/postgres.dump"
docker exec -i engineering-score-postgres-1 pg_restore --list < "$BACKUP/postgres.dump" > "$BACKUP/postgres-list.txt"
tar -czf "$BACKUP/server-source.tar.gz" -C "$APP" server/src
readlink -f /var/www/engineering-score-admin/current > "$BACKUP/web-previous.txt"
docker tag "$(docker inspect --format '{{.Image}}' engineering-score-server-1)" "engineering-score-server:before-v217-$STAMP"
sha256sum "$BACKUP/postgres.dump" "$BACKUP/server-source.tar.gz"
echo "Backup complete: $BACKUP"
tar -xzf /home/ubuntu/release-v217.tar.gz -C "$APP"
cd "$APP/deploy/production"
docker compose -f docker-compose.yml -f docker-compose.host.yml build server
docker compose -f docker-compose.yml -f docker-compose.host.yml up -d --no-deps server
for attempt in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:8080/actuator/health/readiness > /dev/null 2>&1; then break; fi
  sleep 2
done
curl -fsS http://127.0.0.1:8080/actuator/health/readiness
WEB=/var/www/engineering-score-admin/releases/v217-$STAMP
install -d -m 755 "$WEB"
tar -xzf /home/ubuntu/web-v217.tar.gz -C "$WEB"
chmod -R a+rX "$WEB"
test ! -e /var/www/engineering-score-admin/current-next
ln -s "$WEB" /var/www/engineering-score-admin/current-next
mv -Tf /var/www/engineering-score-admin/current-next /var/www/engineering-score-admin/current
echo "Deployed web: $WEB"
docker compose -f docker-compose.yml -f docker-compose.host.yml ps server
