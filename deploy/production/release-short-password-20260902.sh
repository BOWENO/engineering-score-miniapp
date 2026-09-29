#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
APP=/home/ubuntu/apps/engineering-score
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
BACKUP=/var/backups/engineering-score/short-password-$STAMP
mkdir -p "$BACKUP"
docker exec engineering-score-postgres-1 sh -c 'exec pg_dump -U "$POSTGRES_USER" -d engineering_score -Fc' > "$BACKUP/postgres.dump"
test -s "$BACKUP/postgres.dump"
docker exec -i engineering-score-postgres-1 pg_restore --list < "$BACKUP/postgres.dump" > "$BACKUP/postgres-list.txt"
tar -czf "$BACKUP/auth-source.tar.gz" -C "$APP" server/src/main/java/com/acme/performance/auth/web/AdminAuthController.java server/src/main/java/com/acme/performance/auth/web/WechatLoginController.java
readlink -f /var/www/engineering-score-admin/current > "$BACKUP/web-previous.txt"
docker tag "$(docker inspect --format '{{.Image}}' engineering-score-server-1)" "engineering-score-server:before-short-password-$STAMP"
echo "Backup complete: $BACKUP"
tar -xzf /home/ubuntu/short-password-v216.tar.gz -C "$APP"
cd "$APP/deploy/production"
docker compose -f docker-compose.yml -f docker-compose.host.yml build server
docker compose -f docker-compose.yml -f docker-compose.host.yml up -d --no-deps server
for attempt in $(seq 1 45); do
  if curl -fsS http://127.0.0.1:8080/actuator/health/readiness > /dev/null; then break; fi
  sleep 2
done
curl -fsS http://127.0.0.1:8080/actuator/health/readiness
WEB=/var/www/engineering-score-admin/releases/short-password-$STAMP
mkdir -p "$WEB"
cp -a "$APP/admin-web/dist/." "$WEB/"
chmod -R a+rX "$WEB"
ln -s "$WEB" /var/www/engineering-score-admin/current-next
mv -Tf /var/www/engineering-score-admin/current-next /var/www/engineering-score-admin/current
echo "Deployed web: $WEB"
docker compose -f docker-compose.yml -f docker-compose.host.yml ps server
