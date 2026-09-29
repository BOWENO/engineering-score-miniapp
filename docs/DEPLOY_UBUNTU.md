# Ubuntu 24.04 生产部署

目标服务器：`43.163.87.131`  
生产域名：`https://www.testengineering.cloud`

## 前置检查

1. 将 `www.testengineering.cloud` 和根域名的 A 记录解析到 `43.163.87.131`。
2. 云防火墙仅开放 SSH、80 和 443；PostgreSQL、Redis、MinIO及8080不得暴露公网。
3. 安装 Docker Engine 和 Compose 插件，并启用 Docker 开机启动。
4. 将证书放在 `/etc/letsencrypt/live/www.testengineering.cloud/`。若实际路径不同，修改 `deploy/nginx/default.conf` 和 Compose 挂载。
5. 在微信公众平台把 `https://www.testengineering.cloud` 配置为 request、uploadFile 和 downloadFile 合法域名。
6. 上线前由管理员导入员工档案并绑定微信 `openid`；生产登录不会自动创建员工账号。

## 首次部署

```bash
sudo mkdir -p /opt/engineering-score /var/www/certbot
sudo chown "$USER":"$USER" /opt/engineering-score
cd /opt/engineering-score
git clone <repository-url> app
cd app/deploy/production
cp .env.example .env
chmod 600 .env
# 编辑 .env，所有占位值必须替换
docker compose build --pull
docker compose up -d
docker compose ps
curl --fail https://www.testengineering.cloud/api/health
```

Prometheus 仅在 Compose 内网抓取 `/actuator/prometheus`，不映射宿主机端口；Nginx 对公网 `/actuator/` 统一返回 404。需要接入外部监控时，应通过 VPN、SSH 隧道或受鉴权的监控网关访问，不要直接开放 9090。

## 发布更新

```bash
cd /opt/engineering-score/app
git pull --ff-only
cd deploy/production
docker compose build --pull
docker compose up -d --remove-orphans
curl --fail https://www.testengineering.cloud/api/health
```

数据库迁移由服务启动时的 Flyway 自动执行。发布前必须先做数据库备份。禁止使用 `docker compose down -v`，该命令会删除生产数据卷。

## 备份与恢复

首次启用定时备份：

```bash
cd /opt/engineering-score/app/deploy/production
chmod 750 backup.sh restore.sh
sudo mkdir -p /var/backups/engineering-score
sudo chown "$USER":"$USER" /var/backups/engineering-score
./backup.sh
```

建议在 root 的 crontab 中每日 02:30 执行，并将备份目录同步到另一台服务器或云对象存储。脚本默认保留 30 天，可通过 `.env` 的 `BACKUP_ROOT` 和 `RETENTION_DAYS` 调整：

```cron
30 2 * * * cd /opt/engineering-score/app/deploy/production && ./backup.sh >> /var/log/engineering-score-backup.log 2>&1
```

恢复会停止应用、重建业务数据库并覆盖同名对象，必须先在隔离环境演练并确认备份校验值：

```bash
cd /opt/engineering-score/app/deploy/production
sha256sum -c /var/backups/engineering-score/<timestamp>/SHA256SUMS
./restore.sh /var/backups/engineering-score/<timestamp>
curl --fail https://www.testengineering.cloud/api/health
```

至少每季度执行一次恢复演练，记录恢复点目标（RPO）与恢复时间（RTO）。

## 证书续期

若证书由 Certbot 管理，设置续期后重载网关：

```bash
sudo certbot renew --deploy-hook "docker compose -f /opt/engineering-score/app/deploy/production/docker-compose.yml exec -T gateway nginx -s reload"
```

## 回滚

保留上一版本 Git 标签和镜像。应用回滚使用上一标签重新构建；规则变更使用业务规则版本回退；已产生的积分事件不得删除，只能冲正。
