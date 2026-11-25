# 信科 Link V1.0 生产部署手册

本手册面向 **Linux（Ubuntu 22.04/24.04）单机 4GB 内存**环境，覆盖 MySQL 8.4、Redis 7、阿里云 OSS、Java 21 后端、Vue 3 管理后台、Nginx HTTPS/WSS 与微信小程序上线联调。

> 首版默认关闭 Kafka、RocketMQ、Canal CDC 与 Link AI 入口，保持单机稳定、低内存占用与微信审核零风险。

---

## 一、服务器基础环境准备

1. 安装基础软件：
   ```bash
   sudo apt update
   sudo apt install -y openjdk-21-jre-headless nginx certbot python3-certbot-nginx mysql-server redis-server
   ```
2. 创建专用运行账号与目录：
   ```bash
   sudo useradd --system --no-create-home --shell /usr/sbin/nologin xinkelink
   sudo mkdir -p /opt/xinkelink /etc/xinkelink /var/log/xinkelink /var/www/xinkelink-admin /var/lib/xinkelink/images-fallback /var/backups/xinkelink
   sudo chown -R xinkelink:xinkelink /opt/xinkelink /var/log/xinkelink /var/lib/xinkelink
   ```

---

## 二、MySQL 8.4 初始化与定时备份

1. 创建生产库与双账号（迁移账号 + 运行账号）：
   ```sql
   CREATE DATABASE xinkelink_prod CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

   -- 1) Flyway 迁移账号
   CREATE USER 'xinkelink_migrator'@'127.0.0.1' IDENTIFIED BY 'REPLACE_MIGRATOR_PWD';
   GRANT ALL PRIVILEGES ON xinkelink_prod.* TO 'xinkelink_migrator'@'127.0.0.1';

   -- 2) 运行时最小权限账号（参考 scripts/initialize-backend-dev.ps1 最小权限策略）
   CREATE USER 'xinkelink_app'@'127.0.0.1' IDENTIFIED BY 'REPLACE_APP_PWD';
   GRANT SELECT, INSERT, UPDATE, DELETE ON xinkelink_prod.* TO 'xinkelink_app'@'127.0.0.1';
   -- 审计表仅允许查询与追加，禁止修改和删除（首启动 Flyway 执行 V1-V20 后收紧）：
   -- REVOKE UPDATE, DELETE ON xinkelink_prod.xl_admin_audit_log FROM 'xinkelink_app'@'127.0.0.1';
   FLUSH PRIVILEGES;
   ```
2. 首次启动后端时，Flyway 会自动按序执行 `V1` 至 `V20` 全部迁移。
3. **创建生产管理员账号**（使用 BCrypt 12 轮哈希写入 `xl_admin`，勿使用开发测试密码）。
4. **配置每日自动备份（保留 14 天）**：
   ```bash
   sudo tee /etc/cron.d/xinkelink-db-backup >/dev/null <<'EOF'
   0 3 * * * root mysqldump --single-transaction --routines --triggers xinkelink_prod | gzip > /var/backups/xinkelink/xinkelink_$(date +\%F).sql.gz && find /var/backups/xinkelink -name "*.sql.gz" -mtime +14 -delete
   EOF
   ```

---

## 三、Redis 7 与阿里云 OSS 配置

1. **Redis 7**：编辑 `/etc/redis/redis.conf`，确认：
   - `bind 127.0.0.1 ::1`（仅本机监听）
   - `requirepass <强密码>`
   - `maxmemory 256mb`、`maxmemory-policy allkeys-lru`
   - 重启：`sudo systemctl restart redis-server`
2. **阿里云 OSS**：
   - 新建私有读写（Private）生产 Bucket（后端通过短期签名 URL 授权图片访问，切勿设为公共读）。
   - 创建专用 RAM 子账号，仅授予该 Bucket 的 `oss:PutObject`、`oss:GetObject`、`oss:DeleteObject` 权限，填入 `xinkelink.env`。

---

## 四、打包与部署后端 + 管理后台

1. 本地构建产物：
   ```bash
   # 后端可执行 JAR
   cd apps/server && mvn clean package -DskipTests
   # 产物：apps/server/target/xinkelink-server-0.1.0-SNAPSHOT.jar

   # 管理后台静态资源
   cd apps/admin && npm run build
   # 产物：apps/admin/dist/
   ```
2. 上传到服务器：
   - 将 JAR 上传至 `/opt/xinkelink/xinkelink-server.jar`
   - 将 `apps/admin/dist/*` 上传至 `/var/www/xinkelink-admin/`
3. 配置环境变量与 systemd：
   ```bash
   sudo cp deploy/env/xinkelink.env.example /etc/xinkelink/xinkelink.env
   sudo nano /etc/xinkelink/xinkelink.env   # 填入全部真实密钥
   sudo chown root:xinkelink /etc/xinkelink/xinkelink.env
   sudo chmod 640 /etc/xinkelink/xinkelink.env

   sudo cp deploy/systemd/xinkelink-server.service /etc/systemd/system/
   sudo systemctl daemon-reload
   sudo systemctl enable --now xinkelink-server
   ```
4. 配置日志切分（`/etc/logrotate.d/xinkelink`）：
   ```text
   /var/log/xinkelink/*.log {
       daily
       rotate 14
       compress
       delaycompress
       missingok
       notifempty
       copytruncate
   }
   ```
5. 配置 Nginx 与 HTTPS 证书（备案通过、域名解析生效后执行）：
   ```bash
   sudo certbot certonly --webroot -w /var/www/certbot -d your-domain.example.com
   sudo cp deploy/nginx/xinkelink.conf /etc/nginx/sites-available/xinkelink.conf
   sudo sed -i 's/your-domain.example.com/你的正式域名/g' /etc/nginx/sites-available/xinkelink.conf
   sudo ln -sf /etc/nginx/sites-available/xinkelink.conf /etc/nginx/sites-enabled/xinkelink.conf
   sudo nginx -t && sudo systemctl reload nginx
   ```

---

## 五、上线烟测与微信小程序提审

1. **后端健康检查**：
   ```bash
   curl -fsS http://127.0.0.1:18081/api/v1/health
   curl -fsS https://你的正式域名/api/v1/health
   ```
2. **微信公众平台配置**：
   - 开发管理 → 服务器域名：配置 `request`、`uploadFile`、`downloadFile` 为 `https://你的正式域名`，`socket` 为 `wss://你的正式域名`。
   - 设置 → 服务内容声明 → 用户隐私保护指引：声明头像、相册选图、剪贴板用途并提交生效。
3. **小程序切换正式地址并上传体验版**：
   - 将 `apps/miniprogram/miniprogram/backend/config.ts` 中 `BACKEND_ORIGIN` 改为 `https://你的正式域名`。
   - 确认 `project.config.json` 中 `urlCheck: true` 且已排除 `miniprogram/assets/mock`。
   - 真机扫码完成全链路验收：真实微信登录 → 修改资料/头像 → 发帖+传图 → 敏感词拦截测试 → 评论/点赞 → WSS 私信实时收发 → 举报 → 后台审核隐藏 → 退出重登。
4. **提交正式审核**：清理测试脏数据，保留 2–3 条正常校园引导帖与迎新公告后提交审核。

---

## 六、后续 V1.1 恢复 Link AI 说明

本次上线未删除任何 Link AI 代码或表结构。后续准备好学校真实规章、ChromaDB 服务与模型资质后：
1. 在 `app.json` 与 `utils/navigation.ts` 恢复 `Link AI` Tab；
2. 后台导入并发布真实校规，配置 `XINKELINK_AI_*` 环境变量；
3. 重新提审发布 V1.1 即可。
