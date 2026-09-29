# 工程技术员积分绩效小程序

本仓库实现《工程技术员积分绩效小程序技术设计说明书 V1.0》。首期采用模块化单体后端、微信原生小程序和 Vue 3 管理端。

当前业务角色仅包含技术员、助理工程师、主管和部长。系统已实现责任排班、班次基础分、加分/扣分/申诉、D级认定、月度封存、封存后更正、消息提醒，以及 Web 统计、导入导出和基础资料管理。V2.1 将设备异常升级为“一事一档”，覆盖材料收集、人员说明、调查、责任认定、整改、验收、绩效关联、时间线及月度复盘。

## 目录

- `server/`：Java 21 + Spring Boot 3 后端
- `miniprogram/`：微信原生小程序（TypeScript）
- `admin-web/`：Vue 3 + TypeScript 管理端
- `docs/`：架构决策、接口及开发计划
- `deploy/`：本地依赖和部署配置

## 本地启动

1. 复制 `.env.example` 为本地环境变量配置，禁止提交真实密钥。
2. 执行 `docker compose -f deploy/docker-compose.yml up -d` 启动 PostgreSQL、Redis 和 MinIO。
3. 进入 `server/` 执行 `mvn spring-boot:run -Dspring-boot.run.profiles=local`。
4. 使用微信开发者工具导入仓库根目录。
5. 进入 `admin-web/` 执行 `npm install && npm run dev`。

后端启动后可访问 `GET http://localhost:8080/api/health`。

生产部署目标为 Ubuntu 24.04 和 `https://www.testengineering.cloud`，详见 `docs/DEPLOY_UBUNTU.md`。

## 核心约束

- 积分余额不得直接修改，所有变更必须形成不可变 `score_event`。
- 所有已认证写接口由统一过滤器强制要求 `Idempotency-Key`，成功响应可安全重放。
- 更新型接口使用 `version` 乐观锁，冲突返回 HTTP 409。
- 服务端按角色与组织范围鉴权，前端隐藏按钮不构成权限控制。
- 技术员不开放 Web 管理后台；助理工程师、主管和部长按职责获取严格数据范围。
- 微信审核账号和演示数据与正式组织、排名、排班、设备及异常档案完全隔离。
- AppSecret、数据库密码、对象存储密钥不得进入源码或 Git。
