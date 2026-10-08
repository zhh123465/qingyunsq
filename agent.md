# CampusForum Agent Notes

> ⚠️ **本文件已被根目录 [`agents.md`](agents.md) 取代**（后者是更完整、更新的项目理解文档，含完整架构、API 速查表与陷阱清单）。
> 本文件内容是 `agents.md` 的子集，保留作历史参考；如有冲突以 `agents.md` 与源码为准。

本文档给后续自动化 agent 或维护者使用，记录当前工作区的源码事实与操作注意事项。以源码为准，文档和 deploy 配置如果冲突，优先检查 `backend/src/main/resources/*.yml`、Controller、Service 与前端 API 封装。

## 项目概览

- 项目是前后端分离的高校学习社群平台：`backend/` 为 Spring Boot 单体，`frontend/` 为 Vue 3 + Vite 应用。
- 后端入口：`backend/src/main/java/com/campusforum/CampusForumApplication.java`。
- 前端入口：`frontend/src/main.ts`，路由在 `frontend/src/router/index.ts`。
- API 统一前缀：后端为 `/api/v1/**`，前端 `frontend/src/api/request.ts` 的 Axios `baseURL` 固定为 `/api/v1`。
- WebSocket 路径：`/ws/notify`，生产推荐通过 `POST /api/v1/auth/ws-ticket` 获取一次性票据。

## 后端模块事实

- 认证使用 Sa-Token + Redis 持久化 tik token，当前不是 JWT；不要新增 `JWT_SECRET`、`SA_TOKEN_JWT_SECRET_KEY` 这类无效配置。
- 多租户由 `tenant.mode=standalone|multi` 控制。standalone 默认租户为 `tenant.standalone-tenant-id=1`；multi 模式通过子域名或受控的 `X-Tenant-Id` 回退解析。
- AI 调用由 `TenantAwareAiService` 统一委托。租户表 `tenants.ai_config` 中的配置优先；全局 `ai.providers.deepseek/mimo` 作为模型级 fallback。
- `OpenAiCompatService` 会把 base URL 规范化到 `/v1`，当前支持 OpenAI Chat Completions 风格接口。
- AI 工作台接口在 `com.campusforum.ai.workspace`，当前使用 `backend/data/ai-workspace.json` 轻量持久化，适合演示/原型，不是最终数据库模型。
- 存储抽象为 `StorageService`，当前实现只有 OSS 与 Local：生产默认 `storage.type=oss`，CI/test 使用 `local`。源码中已没有 MinIO 实现。
- 搜索支持 MeiliSearch 与 MySQL 兜底，`search.type=mysql|meilisearch`。
- 安全启动校验在 `SecurityStartupValidator`，生产必须设置 `SIGNED_URL_SECRET`、`CRYPTO_MASTER_KEY`、Redis 强密码、CORS 与 WS 来源。

## 前端模块事实

- 技术栈：Vue 3、Vite 5、TypeScript、Naive UI、Pinia、Vue Router、vue-i18n、vite-plugin-pwa、Tailwind/PostCSS。
- 登录 token 保存在 `localStorage.token`，请求头写入 `Authorization`，不带 `Bearer` 前缀。
- 前端不会再主动注入 `X-Tenant-Id`；租户身份由服务端从会话或解析器权威决定。
- 主要页面包括首页、广场、帖子、空间、资源、打卡、通知、私信、积分、AI 助手和管理后台。

## 部署配置

- `deploy/docker-compose.yml` 当前服务：`nginx`、`app`、`mysql`、`redis`、`meilisearch`。对象存储走外部 OSS；local 模式会挂载 `local_uploads` 到 `/app/uploads`。
- `deploy/.env.example` 是生产变量模板，`STORAGE_TYPE=oss|local`。oss 模式需要 `STORAGE_OSS_ENDPOINT`、`OSS_ACCESS_KEY`、`OSS_SECRET_KEY`、`OSS_BUCKET`。
- nginx 配置在 `deploy/nginx/nginx.conf`，会代理 `/api/` 与 `/ws/`，并屏蔽 actuator、Swagger/Knife4j 路径。
- `deploy/install.sh` 会自动生成 `SIGNED_URL_SECRET` 与 `CRYPTO_MASTER_KEY`，并拒绝包含 `ChangeMe` 等弱占位值的生产变量。

## 常用命令

后端：

```bash
cd backend
mvn test
mvn spring-boot:run
```

前端：

```bash
cd frontend
npm ci
npm run lint
npm run test
npm run build
```

部署：

```bash
cd deploy
cp .env.example .env
# 编辑 .env 后：
bash install.sh
```

## 注意事项

- 当前工作区可能存在大量未提交修改与删除项。不要回滚不是自己造成的改动。
- 不要删除工作区外文件；如确实需要删除，必须先向用户确认。
- 任何具备时效性的外部服务配置，例如 AI Provider 官方 base URL，决策前必须联网确认。
- `rg.exe` 在当前环境可能存在但执行会被拒绝，可改用 PowerShell `Get-ChildItem` 和 `Select-String`。
- 本机当前未检测到 Docker，无法在本地执行 `docker compose config` 或启动 compose；如需验证 compose，需要安装 Docker。
- `backend/Dockerfile` 使用 `eclipse-temurin:21-jre-alpine` 运行 jar，项目源码编译目标仍是 Java 17。
