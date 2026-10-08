# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.
记住 我让你执行的任务都是基于当前文件夹进行的，项目源代码就在当前文件夹，上下文请查看agents.md，完成任务后记得维护上下文。
如果遇到访问外网不通问题，可以让命令临时走7890端口，我配置好代理过的。这里的外网指的是海外的网络资源，如果是访问国内的那就不用代理，访问国外的需要。

## 上下文加载规则（务必遵守）

**每次任务开始前必读**：先并行阅读根目录的 `CLAUDE.md`（本文件）和 `agents.md`（**注意是复数**）来建立项目认知。

- `agents.md`（复数）：完整、最新的项目理解文档（架构、API 速查表、陷阱清单、TODO），由维护者持续更新
- `agent.md`（单数）：**已废弃**的历史子集，冲突时以 `agents.md` 和源码为准。**不要读单数版本**，会漏掉大量新事实
- 任务结束后若有必要，记得维护 `agents.md`（复数）上下文

## 交流规则

**所有与用户的交流必须使用中文**，包括但不限于：计划说明、进度汇报、问题澄清、代码解释。代码、技术术语、文件路径等保持原文。

## Project Overview

CampusForum（小青知识库）— 前后端分离 + 多租户 + AI 增强的高校轻量化学习社群平台。
- **后端**: Spring Boot 3.3 (Java 17 编译，Docker JRE 21 运行) + MyBatis-Plus 3.5 + Sa-Token 1.38
- **前端**: Vue 3.5 + Vite 5 + Naive UI 2.39 + Tailwind 3.4
- **部署**: Docker Compose（nginx + app + mysql + redis + meilisearch），生产运行于本服务器

## Build & Deploy Commands

```bash
# 后端编译（必须在 backend/ 目录下执行）
cd /root/projects/campus/backend
mvn -o compile              # 离线编译
mvn -o package -DskipTests  # 离线打包
mvn test                    # 需要 Docker/Testcontainers

# 前端构建（必须在 frontend/ 目录下执行）
cd /root/projects/campus/frontend
npm run build               # vue-tsc 类型检查 + vite build

# 生产部署（JAR 构建后执行）
cd /root/projects/campus/deploy
docker compose up -d --build app    # 重建并重启应用容器
docker compose restart nginx        # 前端 dist 直挂 nginx，无需重建

# 生产数据库直连
docker exec deploy-mysql-1 mysql -uroot -p"$(grep MYSQL_ROOT_PASSWORD deploy/.env | cut -d= -f2)" campus_forum -e "QUERY"
```

## Critical Architecture Rules

### Authentication
- **Sa-Token tik 随机 token，不是 JWT**。token 存 `Authorization` header（**不带 Bearer 前缀**），配置 `sa-token.token-name: Authorization`
- **GET 默认游客可读**，写操作（POST/PUT/DELETE/PATCH）一律要求 `StpUtil.checkLogin()`
- 角色: `GUEST` / 普通用户 / `TENANT_ADMIN` / `SUPER_ADMIN`

### Multi-Tenant Isolation
- `TenantContext` (ThreadLocal) + MyBatis-Plus 租户插件自动注入 `tenant_id`
- **跨线程必须手动传递** tenantId（`@Async`、WebSocket、定时任务）
- 全局共享表（无 tenant_id）需加入 `MyBatisPlusConfig.TENANT_IGNORE_TABLES`
- Session 里 tenantId 反序列化后**可能是 Integer 而非 Long**，访问时一律用 `Number` 取值再 `longValue()`

### Database Migrations
- **改表 = 同时改 `db/schema.sql` + 加 `migrations/V*.sql`**
- Flyway classpath 路径: `backend/src/main/resources/db/migration/`
- 启动时自动执行新迁移；生产库已 baseline `20260624_01`

### Frontend Conventions
- `src/api/request.ts` 解包后端 `R<T>` 响应：`code !== 0` 一律 reject
- `Authorization` header 无 Bearer 前缀，前端不注入 `X-Tenant-Id`
- 新 API 层按模块分文件：`api/auth.ts`, `api/ai-workspace.ts`, `api/learning.ts` 等

### Outbound HTTP
- 所有出站请求使用 `SafeHttpClient.build(connMs, readMs)` 获取带 SSRF 防护的 RestTemplate
- HTML 用户内容写库前经 `HtmlSanitizerService.sanitizePost/Comment/Message()` 清洗
- 文件上传用 `MimeTypeValidator`（Tika 真实 MIME + 扩展名交叉验证 + 全局黑名单）

### AI Rate Limiting
- `AiRateLimitInterceptor` + `AiRequestBodyCacheFilter` 实现 model-aware 分级限流
- Pro 模型（deepseek-v4-pro, mimo-v2.5-pro）: 每人每小时 10 次
- 普通模型: 每人每小时 20 次
- Redis key: `ai_rate:user:{userId}:hour:{pro|normal}`

## Key File Locations

| Purpose | Path |
|---|---|
| Complete project docs | `agents.md` (global reference) |
| API list (all 45+ endpoints) | `agents.md` §7 |
| Sa-Token interceptor rules | `backend/.../security/SaTokenConfig.java` |
| Tenant plugin config | `backend/.../infra/MyBatisPlusConfig.java` |
| AI crawler service | `backend/.../learning/service/LearningCrawlerService.java` |
| Rate limit interceptor | `backend/.../ai/ratelimit/AiRateLimitInterceptor.java` |
| HTML sanitizer | `backend/.../infra/sanitize/HtmlSanitizerService.java` |
| Safe HTTP client (SSRF) | `backend/.../infra/security/SafeHttpClient.java` |
| Vue router + auth guard | `frontend/src/router/index.ts` |
| AI model constants | `frontend/src/types/ai.ts` `AI_MODELS` |
| Tutorial static data | `frontend/src/data/tutorials.ts` |
| CORS config | `backend/.../infra/security/CorsConfig.java` |
| Database schema | `db/schema.sql` |
| Flyway migrations | `db/migrations/` + `backend/src/main/resources/db/migration/` |
| Docker compose | `deploy/docker-compose.yml` |
| Nginx config | `deploy/nginx/nginx.conf` |
| Production env (untracked) | `deploy/.env` |
