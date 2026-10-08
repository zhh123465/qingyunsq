# AGENTS.md — CampusForum 项目全局理解文档

> 本文件供 AI 编码助手（Claude Code / Codex / Cursor 等）与人类维护者快速建立项目认知，**避免每次任务都重新通读源码**，每次做完一个任务记得更新上下文。
>
> **维护原则**：以**源码 + 服务器实际部署状态**为事实来源，注意，你现在就是在云服务器上，网站前后端都部署这上面正在对外提供服务。本文档与部署状态冲突时，以实际运行的容器/配置为准，并请顺手更新本文件。
> **网站状态**：当前导航栏：【资源】|【打卡】|【学习】|【AI助手】。【学习】页已实现（精选教程+公开笔记+教程阅读器）。【工具】、【软件】占位标签已移除（2026-06-28）。
> **最后整理**：2026-07-16（Claude Code，**笔记发布审核流上线**：ai_notes 状态机扩展 pending/rejected + 编辑重审 + 管理端 approve/reject/batch-approve + 超管邮件提醒(10min 节流，key 与资源独立)，对齐 2026-07-13 的资源审核流，见 §14 顶部条目）。上一次：2026-07-13（资源上传审核流上线，见 §14.2；GitHub OAuth 凭据已配置生效）。
> 另有一份更简短的历史笔记 `agent.md`（单数），内容是本文件的子集，已被本文件取代。

## 在执行所有任务时都需要遵循的规则：
1. 不可删除工作区以外的任何文件但可以自由查看，如确实需要删除则需要向用户确认
2. 对于我要求的一个任务中若遇到没有安装的工具需向我说明后自行安装，不可使用其他更次的策略降级处理，牢记没有想要的工具那就自己安装，不要老是因为没有某个工具就导致任务要分好几轮才搞好
3. 对于任何具备时效性的信息都需要联网搜索确认最新情况再进行决策，比如让你配置某个ai服务，其最新base url地址就需要联网搜索，或者让你导入某些文档资料，生成一些数据之类的都需要自行去联网搜索下载
4. 对于某个任务若遇到解决不了的问题，若该问题不影响剩余任务进度进行则先记录下来先执行完其他不受影响的任务，最后将能进行的任务都进行完之后进行完整的任务汇报，需说明已完成情况、遇到的问题、推荐解决方案
5. 对于我的提示词，你需要判断是询问还是任务命令，有些是属于单纯询问问题的就不要擅自修改任何文件
除以上规则外，你可自由行动

---

## 0. 一句话概览

CampusForum 是**前后端分离 + 多租户 + AI 增强**的高校轻量化学习社群平台。
后端 Spring Boot 3 单体（源码编译目标 Java 17 / Docker JRE 21 运行），前端 Vue 3 + Vite SPA（带 PWA）。
**当前生产部署于本服务器**（Docker Compose + 1Panel 运维），域名为"小青知识库"。
核心特色：①一套代码支持单校（standalone）与多校 SaaS（multi）部署；②每租户可独立配置 AI Provider；③经过一轮系统性安全加固。

---

## 1. 技术栈与版本

| 层 | 选型 | 关键版本 |
|---|---|---|
| 后端框架 | Spring Boot | 3.3.0（parent） |
| 语言/编译目标 | Java | 源码 `pom.xml` 编译目标 **17**；Dockerfile（及线上容器）实际用 **`temurin:21-jre`** 运行 |
| ORM | MyBatis-Plus | 3.5.7（逻辑删除字段 `deleted`） |
| 认证 | Sa-Token | 1.38.0（**tik 随机 token + Redis 持久化，非 JWT**） |
| AI | LangChain4j + 自研 OpenAI 兼容客户端 | 0.34.0 |
| 数据库 | MySQL | 8.0 |
| 缓存 | Redis 7 + Caffeine | — |
| 搜索 | MeiliSearch（MySQL FULLTEXT/LIKE 兜底）——**2026-07-12 起 posts/users/resources/spaces 四类全部走 Meili 优先**（`SearchIndexService` 统一写入，写路径同步 + `/admin/search/reindex` 全量重建；users 索引不含 email/studentNo，保持漏洞 9 的 PII 约束） | meili v1.9 |
| 对象存储 | 阿里云 OSS（生产启用）/ Local（dev/test）/ MinIO（容器仍运行但 **app 未使用**，孤儿容器） | aliyun-sdk-oss 3.17.4 |
| 文件类型检测 | Apache Tika | 2.9.2 |
| HTML 消毒 | OWASP Java HTML Sanitizer | 20240325.1 |
| 导出 | Apache POI（XLSX） | 5.2.5 |
| API 文档 | Knife4j / springdoc | 4.5.0（**生产默认关闭**） |
| 前端框架 | Vue 3.5 + Composition API + `<script setup>` | — |
| 构建 | Vite 5.4 | — |
| UI | Naive UI 2.39（按需自动引入） | — |
| 状态 | Pinia 2.2 | — |
| 路由 | Vue Router 4.4 | — |
| i18n | vue-i18n 9.14（zh-CN / en-US） | — |
| 样式 | Tailwind 3.4 + SCSS（sass） | — |
| PWA | vite-plugin-pwa 0.20 | — |
| HTTP | axios 1.7 | — |
| 实时 | Spring 原生 WebSocket（后端 `/ws/notify`）/ 前端原生 `WebSocket` | 前端用浏览器原生 `WebSocket`（非 socket.io），见 §9 |

后端约 **293 个 Java 文件**，35 个 `@RestController`，37 个 `@Service`（2026-07-11 实测）。前端 29 个顶层页面 + 4 个 AI 子页面 + **14 个管理后台页面**（含 ai-config/sensitive-words/tenants/announcements 等）。

---

## 2. 目录结构

```
campus/
├─ backend/                 # Spring Boot 应用
│  ├─ src/main/java/com/campusforum/
│  │  ├─ CampusForumApplication.java   # 入口：@EnableScheduling @EnableAsync @EnableConfigurationProperties(TenantProperties)
│  │  ├─ common/            # 全局：R(统一响应) / ErrorCode / BusinessException / GlobalExceptionHandler / BaseEntity / TokenHasher
│  │  ├─ tenant/            # 多租户核心（resolver/filter/interceptor/cache/audit/websocket）
│  │  ├─ user/              # 用户 + 认证（AuthController/UserController）
│  │  ├─ security/          # SaTokenConfig（拦截器规则）
│  │  ├─ post/              # 帖子 + 评论 + 收藏 + 反应(点赞)
│  │  ├─ space/             # 学习空间（MAJOR/CLASS/CLUB/INTEREST 四类；前端入口已废弃见 §9）
│  │  ├─ resource/          # 学习资源（文件上传/下载/预览）
│  │  ├─ ai/                # AI 能力（chat/summarize/moderate/tags/RAG）+ workspace(知识库/笔记/对话 + KbRagService 真实检索) + post-card
│  │  ├─ wechat/            # 微信小程序登录（code2session）
│  │  ├─ social/            # GitHub OAuth 授权码登录（2026-07-12 接通，GithubLoginService/GithubOAuthClient，海外 API 走 7890 代理）；QQ 仍是无接线骨架
│  │  ├─ checkin/           # 打卡挑战
│  │  ├─ qa/                # 问答（采纳答案）
│  │  ├─ message/           # 私信
│  │  ├─ notify/            # 站内通知
│  │  ├─ follow/            # 关注关系
│  │  ├─ report/            # 举报 + 后台处理
│  │  ├─ achievement/       # 成就/勋章
│  │  ├─ sensitive/         # 敏感词管理
│  │  ├─ search/            # 搜索 + 重建索引
│  │  ├─ admin/             # 管理后台（dashboard/用户/帖子/空间/审计日志/导出）
│  │  └─ infra/             # 横切基础设施（见 §6）
│  ├─ src/main/resources/   # application.yml / application-dev.yml / application-prod.yml + mapper XML
│  ├─ src/test/             # JUnit5 + Testcontainers + jqwik 属性测试
│  └─ Dockerfile
├─ frontend/                # Vue3 + Vite
│  ├─ src/{api,pages,components,stores,composables,utils,locales,router,layout,types}/
│  └─ public/               # PWA: manifest.webmanifest / sw.js / registerSW.js
├─ deploy/                  # docker-compose.yml / nginx/ / install.sh / SECURITY.md / .env.example
├─ db/                      # schema.sql + migrations/（手工管理，见 §8）
├─ docs/                    # 需求/设计/开发指南
├─ 01-需求分析文档.md / 02-技术设计文档.md  # 早期设计文档（中文）
└─ README.md / CHANGELOG.md / agent.md(旧) / agents.md(本文件)
```

---

## 3. 请求全链路（必读）

一个 `/api/v1/**` 请求依次经过：

1. **nginx**（生产）：域名 `qingyunsq.top`/`www.qingyunsq.top`（硬编码于 nginx.conf，`APP_DOMAIN` 变量只影响 install.sh 提示文字），80 → 301 → 443 HTTPS；屏蔽 `/actuator/*`（仅放行内网 prometheus）、swagger/api-docs、隐藏文件、1Panel 探测路径；`client_max_body_size 60m`；代理 `/api/` `/ws/` 到 `app:8080`（**`/uploads/` 直链反代已废弃**，改用 `/api/v1/resources/{id}/signed-url` 签名 URL，见 SECURITY.md §88）；5 类安全响应头（CSP/X-Frame-Options/HSTS/Referrer-Policy/Permissions-Policy）；前端静态资源 `try_files`。"旧产品入口迁移"302 现仅保留 `/square*`→`/resources` 与 `= /spaces`（列表页）→`/learning`；**`/checkin*` 与 `/spaces/{id}` 的 302 劫持已于 2026-07-12 移除**（活跃 SPA 路由交给 try_files 兜底）。⚠ 注意：nginx.conf 是单文件 bind mount，用编辑工具改完（inode 变了）必须 `docker compose restart nginx` 而非 `nginx -s reload` 才能生效。
2. **MdcTraceIdFilter**：写入 `traceId` / `tenantId` 到 SLF4J MDC（响应体 `R.traceId` 与日志 `%X{traceId}` 对齐，便于排障）。
3. **DocAccessFilter**（`@Order(HIGHEST_PRECEDENCE+1)`）：对文档路径做 profile + 来源 IP 双重裁决，不通过直接 404。
4. **TenantResolutionFilter**（`@Order(HIGHEST_PRECEDENCE+50)` 附近）：调用 `TenantResolver` 解析租户，写入 `TenantContext`（ThreadLocal）。解析失败直接写 JSON 错误（`TENANT_NOT_RESOLVED` / 400），不进入 MVC。
5. **RateLimitInterceptor**：基于 Redis 的滑动/计数限流，按"路由模板"（`{id}` 占位）聚合配额，支持端点级 override（见 `application.yml` `rate-limit.overrides`）。
6. **Sa-Token SaInterceptor**（`SaTokenConfig`）：鉴权规则见下。
7. **TenantBindingCheckInterceptor**：校验客户端自报租户与 session 租户一致性（防跨租户）。
8. **Controller → Service → Mapper**。
9. **GlobalExceptionHandler**（`@RestControllerAdvice`）：统一异常 → `R.fail(...)`。

### 统一响应体 `R<T>`（`common/R.java`）
```json
{ "code": 0, "message": "ok", "data": {...}, "traceId": "ab12cd34" }
```
- `code == 0` 表示成功；非 0 为业务错误码（见 `ErrorCode`）。
- **前端 `request.ts` 约定**：`code !== 0` 一律 `Promise.reject(new Error(message))`。

### 鉴权规则（`security/SaTokenConfig.java`）— 重要
- 仅对 `/api/v1/**` 生效。
- **完全放行**（`PUBLIC_AUTH_PATHS`）：`login` `wechat-login` `register` `email-code` `email-exists` `forgot-password` `reset-password`。
- **游客可访问**（无副作用）：`/tenant/info`、`/resources/*/download`、`/resources/*/preview`、`/ai/post-cards/batch`。
- **GET**：默认放行（游客可读广场/资源/帖子），但 `auth/me`、`notifications`、`messages`、含 `/follow` 的路径强制登录。
- **所有写操作（POST/PUT/DELETE/PATCH）一律 `checkLogin()`**。
- 角色：`GUEST` / 普通用户 / `TENANT_ADMIN` / `SUPER_ADMIN`（前端路由 `requiresAdmin` 校验后两者）。

---

## 4. 多租户机制（架构核心，改动需谨慎）

配置前缀 `tenant.*`（`TenantProperties`）：
- `tenant.mode = standalone | multi`（默认 standalone）
- `standalone-tenant-id = 1`（必须等于 `tenants` 表中某条 `status=1` 记录）
- `root-domain`、`allow-header-fallback`、`cache.{max-size,ttl}`

**解析器**（`TenantResolver` 接口，按 `tenant.mode` 二选一注入）：
- `StandaloneTenantResolver`（`matchIfMissing=true`）：永远返回固定 `standaloneTenantId`，来源 `STANDALONE_FIXED`。
- `MultiTenantResolver`：
  - **已登录**：以 Sa-Token Session 的 `tenantId` 为权威；若同时能从子域名解析出租户且与 session **不一致** → 判定"视觉钓鱼 / 跨租户复用 token"，写审计 + `tenant_violation_total` 指标，抛 `TENANT_MISMATCH`。
  - **未登录**：子域名 → `X-Tenant-Id` header（受 `allow-header-fallback` 控制）→ 都不中则 `NO_RESOLVER_MATCHED`。
  - ⚠ Session 里 `tenantId` 经 `sa-token-redis-jackson` 反序列化回来**可能是 Integer 而非 Long**，代码统一按 `Number` 取值再 `longValue()`——新代码访问 session 数值字段务必照此防御，否则 `ClassCastException`。

**传递**：`TenantContext`（ThreadLocal，持有 `tenantId` + `tenantCode`），由 `TenantResolutionFilter` 在 finally 中 `clear()`。
**数据隔离**：MyBatis-Plus 租户插件（`infra/MyBatisPlusConfig` + `CampusMetaObjectHandler`）自动给 SQL 注入 `tenant_id`。异步/定时任务中若没有手动 set，访问租户表会抛 `TenantContextMissingException`（→ 503）。

> **给 AI 的提醒**：跨线程（`@Async`、WebSocket handler、定时任务）使用租户数据时，必须在主线程快照 tenantId 并显式传递/重设，ThreadLocal 不会自动跨线程传播。

---

## 5. AI 子系统

### 5.1 调用委托：`TenantAwareAiService`（`@Primary` 的 `AiService` 实现）
- 所有 AI 调用入口。按 `TenantContext` 解析**租户专属 AI 配置**（存 `tenants.ai_config`，AES-GCM 加密），委托给对应的 `OpenAiCompatService` 实例。
- **客户端缓存**：按 `tenantId` 缓存，指纹 = `baseUrl|sha256(apiKey)|model`，配置变更时 `TenantService.updateAiConfig` 调 `evict(tenantId)` 失效。
- **多级 fallback**：
  - 无 tenantId / provider 非 openai / apiKey 空 → `MockAiService`（本地假回复）。
  - 指定 `model` 参数时优先用全局 `ai.providers.{deepseek,mimo}` 配置（`AiProviderProperties.resolveByModel`）。
  - 上游返回错误（`OpenAiCompatService.isUpstreamError`）→ 降级到 `MockAiService`。
- **fail-loud**：apiKey 解密失败（`CryptoException`）→ 写 `AI_DECRYPT_FAIL` 审计 + 指标，prod 直接抛 `AI_SERVICE_UNAVAILABLE`（dev 降级 mock）。
- **SSRF 防御**：租户配置的 `baseUrl` 经 `PrivateNetworkValidator.requirePublic` 校验，命中内网/本机/链路本地/云元数据 → 写 `AI_SSRF_BLOCKED` 审计 + 降级 mock。

### 5.2 其他 AI 能力
- `AiController`（`/api/v1/ai`）：`summarize` `moderate` `tags` `chat` `rag-chat` `post-card/{postId}` `post-cards/batch`(游客可批量取帖子AI卡片)。
- `RagChatService`：**站内内容检索增强问答**——检索源是论坛 posts/resources/spaces 的搜索索引（走 `SearchService`），与 AI 工作台"知识库"（`ai_kb_documents`）**无关**，勿被"RAG"名称误导。
- ✅ **AI 工作台知识库已是真实 RAG**（2026-07-12 重写）：文档上传时 `KbRagService.ingestDocument` 抽取正文（PDF→PDFBox 2.0.31 / docx·pptx·xlsx→POI / html→jsoup / 文本直读）→ 按段落切块（~800 字/块，单文档上限 500 块）写入 `ai_kb_chunks`，`vectorCount` = 真实切块数（旧的 `uploaded*512` 假算式已删）；对话发消息时 `buildConvContext → KbRagService.retrieveContext` 按 query 分词（ASCII 词 + 中文 2-gram）对**挂载知识库的切块 + 问答对 + 本人笔记（标题命中→OSS 拉正文）+ 公开教程章节**做词频打分取 top-N，拼装 ≤6000 字上下文注入 prompt。检索是词法打分而非向量（校园规模够用，无 embedding 依赖）；越权防护：仅检索 owner 本人或 shared 的库。**存量文档（重写前上传的）没有切块**，需删除重传才参与检索。
- ✅ **Agent/插件市场已整体裁撤**（2026-07-12）：48 端点从未被前端接入，按产品决策删除——Controller/Service 相关代码、AiAgent/AiPlugin/AiUserInstalledPlugin 实体与 Mapper、3 张表（迁移 `V20260712_01` DROP）、ai_conversations/ai_messages 的 agent_id/plugin_ids 列全部清除；欢迎知识库种子 QA 从 9 条减为 8 条（删了 Agent 介绍）。
- `PostAiCardService`：为帖子生成 AI 摘要卡片（落 `post_ai_cards` 表，含 hot_comment / highlights）。
- `AiWorkspaceController`（`/api/v1/ai/knowledge-bases|conversations|notes`）：**知识库/对话/笔记工作台**（agents/plugins 端点已删）。2026-06-24 从 JSON 文件迁移到 MySQL。

### 5.3 模型选择与限流（2026-06-30 新增）

**4 个可用模型**（前端 `types/ai.ts` `AI_MODELS`）：
| 模型ID | 名称 | 类型 |
|--------|------|------|
| `mimo-v2.5` | MiMo 2.5 | normal |
| `deepseek-v4-flash` | DeepSeek V4 Flash | normal |
| `deepseek-v4-pro` | DeepSeek V4 Pro | pro |
| `mimo-v2.5-pro` | MiMo 2.5 Pro | pro |

**默认模型**：`mimo-v2.5`（所有 AI 对话框默认使用）。

**模型切换能力**：
- AI知识库主聊天页（AiChat.vue）：可切换全部 4 个模型（药丸按钮下拉菜单）
- 笔记编辑器内嵌AI（KnowledgeNotes.vue）、教程右侧AI（TutorialReader.vue）、公开笔记右侧AI（NotePublicDetail.vue）：固定使用 `mimo-v2.5`，不提供切换。（旧文档提到的"浮动AI助手"在当前代码中无对应组件，2026-07-11 全前端 grep 无匹配，已删除该提法。）

**分级限流**（`AiRateLimitInterceptor` + `AiRequestBodyCacheFilter`）：
- Pro 模型：每人每小时 10 次
- 普通模型：每人每小时 20 次
- 还有 per-user/min（5/min）和 per-tenant/day（1000/day）
- Redis key: `ai_rate:user:{userId}:hour:{pro|normal}`（3600s 滑动窗口）
- 前端通过 `GET /api/v1/ai/rate-limit-status` 查询剩余次数并展示

### 5.4 配置（`ai.*`）
- ~~`ai.provider` / `base-url` / `api-key` / `model` 顶层配置~~ **已于 2026-07-11 删除**（application*.yml / docker-compose.yml / .env / .env.example 四处），本就是代码从不读取的死配置。现只剩 `ai.providers.{deepseek,mimo}`（全局 provider 池）+ `ai.rate-limit.*` + `ai.http.*`。
- `ai.rate-limit.{per-user-per-min=5, pro-per-user-per-hour=10, normal-per-user-per-hour=20, per-tenant-per-day=1000}`；`ai.http.{connect-timeout=8s, read-timeout=30s}`。另外 `AiRateLimitInterceptor` 还拦截 `/ai/conversations/{id}/messages`（plugins invoke 行随插件市场裁撤删除）；其 `/ai/post-card/{id}` 分支是死代码（只认 POST 但端点是 GET）。
- ⚠ **线上实际状态（2026-07-11 核实：AI 真实可用，非 mock）**，生效路径有三条：
  - ① 租户 1 的 `tenants.ai_config` 已配置 `{provider: openai, baseUrl: https://api.deepseek.com, model: deepseek-v4-flash}`（apiKey AES-GCM 加密，encVersion 2）→ 所有**不带 model 参数**的 AI 调用（summarize/moderate/tags/post-card/无模型 chat）真实调用 DeepSeek；
  - ② `.env` 已配 `MIMO_API_KEY`（注入 `ai.providers.mimo`）→ 前端默认模型 `mimo-v2.5` 及 `mimo-v2.5-pro` 真实调用小米 MiMo API；
  - ③ `DEEPSEEK_API_KEY` 为空 → `deepseek-*` 模型在 `delegate(model)` 中因 provider key 空而回落 `delegateTenantWithRequestedModel`，用租户 ai_config 的 DeepSeek key 真实调用。
  - **陷阱（历史教训）**：曾存在的 `AI_PROVIDER=mock` 环境变量是死配置——全代码库无任何位置读取 `ai.provider`（仅 `AiProviderProperties` 绑定 `ai.providers.*` 复数前缀），本文档旧版曾据此误判"AI 未启用"。该组死配置（AI_PROVIDER/AI_API_KEY/AI_BASE_URL/AI_MODEL + 无引用的 JWT_SECRET）已于 2026-07-11 从 .env / .env.example / docker-compose.yml / application*.yml 全部清除。佐证 AI 真实可用：`ai_messages` 表 2026-07-05 起存有真实 LLM 结构化回复。

---

## 6. 基础设施 `infra/`（横切关注点）

| 子包/类 | 职责 |
|---|---|
| `StorageService` | 对象存储抽象。实现：`OssStorageService`（生产）、`LocalStorageService`（dev/test，`TODO(prod)` 明确不可用于生产）。**推荐 4 参 `upload(in, name, type, size)`**，旧 3 参 `@Deprecated(forRemoval)`。`issuePublicGetUrl` 颁发头像/封面短期公开 URL。**当前生产使用阿里云 OSS（北京 region）**。源码无 MinIO 实现（服务端有孤儿 `deploy-minio-1` 容器遗留自旧 compose 版本，app 不使用）。 |
| `security/SignedUrlService` | HMAC 签名 URL（资源直链下载/预览），TTL `security.signed-url-ttl-seconds`。验证失败统一报 404，不区分"过期/不存在"。 |
| `security/CryptoService`(crypto/) | AES-GCM 加密（租户 apiKey 等）。含 v1 ECB legacy 兼容期（`legacy-cutover-date`，到期 prod 强制关闭）。 |
| `security/WsTicketService` | WebSocket 一次性票据（`POST /auth/ws-ticket`），`ws-ticket.enforced` + cutover 日期控制是否强制。 |
| `security/MimeTypeValidator` | 上传文件用 Tika 检测真实 MIME，与扩展名交叉验证；全局 MIME 黑名单（`security.upload.blocked-mime-types`，拦截 PHP/PE/ELF/脚本等"改名木马"）。 |
| `security/LoginLockoutService` | 登录失败锁定（账号 + IP 双维度，`security.login-lockout.*`）。 |
| `security/PrivateNetworkValidator` / `SafeHttpClient` / `RedirectFollower` / `TrustedProxyResolver` | SSRF 防御工具集：出站 URL 私网校验、安全 HTTP 客户端、可信代理识别（`security.trusted-proxies`）。 |
| `security/SecurityStartupValidator` | **启动期严格校验**：prod 下密钥强度/弱默认值/敏感路径不可被限流排除/cutover 日期，违规抛 `IllegalStateException` 阻止启动；dev 仅 WARN。 |
| `security/DocAccessFilter` / `CorsConfig` | API 文档访问裁决；CORS（`security.cors.allowed-origins`）。 |
| `sanitize/HtmlSanitizerService` | 富文本 XSS 消毒（OWASP），有 jqwik 属性测试保证安全 + 幂等。 |
| `audit/AuditLogService` + `AuditContext` | 安全审计落 `audit_logs`（含 uri/method/ip/ua）。 |
| `metrics/SecurityMetrics` | Micrometer 安全指标（`tenant_violation_total` `ssrf_blocked_total` `crypto_decrypt_failed_total` 等），经 `/actuator/prometheus` 暴露。 |
| `email/` | `EmailService` 接口；`SmtpEmailService`(生产) / `LogEmailService`(mock，`email.mock-enabled`)。 |
| `ratelimit/RateLimitInterceptor` + `RouteTemplateExtractor` | 见 §3。 |
| `websocket/` | WebSocket 端点 `/ws/notify` 配置 + `TenantHandshakeInterceptor`（握手期绑定租户）。 |
| `preview/PreviewProperties` | 文件预览配置（`preview.office-service-url` / `max-preview-size=50MB`）。 |
| `web/MdcTraceIdFilter` / `WebMvcConfig` / `AsyncConfig` / `Knife4jConfig` / `MyBatisPlusConfig` / `CampusMetaObjectHandler` | trace、MVC、异步线程池、文档、MyBatis-Plus 配置、自动填充（createTime/tenantId 等）。 |

---

## 7. 完整 API 速查表（`/api/v1` 前缀）

> 路径中 `{x}` 为 path variable。仅列路由，参数/DTO 以 Controller 源码为准。

**认证 `user/AuthController` `/auth`**：`POST register|email-code|email-exists|login|wechat-login|github-login|logout|forgot-password|reset-password|ws-ticket`，`GET me`、`GET github/authorize-url`（返回 GitHub 授权页 URL + enabled 标志；未配置凭据时 enabled=false），`PUT password`（github-login 已加入 PUBLIC_AUTH_PATHS，2026-07-12）
**用户 `user/UserController` `/users`**：`GET|PUT me`，`POST me/assets`(头像/封面)，`GET|PUT me/mute-settings`，`GET|PUT me/tag-subscriptions`，`GET me/favorites`，`GET {id}`
**帖子 `post/PostController` `/posts`**：`POST`、`GET`(列表)、`GET|PUT|DELETE {id}`、`POST {id}/reactions`
**评论 `post/CommentController`**：`POST|GET /posts/{postId}/comments`，`PUT|DELETE /comments/{id}`，`POST /comments/{id}/reactions`
**空间 `space/SpaceController` `/spaces`**：`POST`、`GET`、`GET|PUT|DELETE {id}`、`POST {id}/join|leave`、`GET {id}/members`、`PUT {id}/members/{userId}`、`GET {id}/posts`、`GET {id}/posts/all`、`PUT {id}/posts/{postId}/status`
**资源 `resource/ResourceController` `/resources`**：`POST`（上传，2026-07-13 起普通用户落 status=2 待审核，管理员免审）、`GET`(列表，仅 status=1)、`GET mine`（本人全部状态资源，须登录）、`GET {id}`、`GET {id}/signed-url`、`GET {id}/download`、`GET {id}/preview`、`GET {id}/preview-text`、`DELETE {id}`
**AI `ai/AiController` `/ai`**：`POST summarize|moderate|tags|chat|rag-chat`、`GET post-card/{postId}`、`POST post-cards/batch`
**AI 工作台 `ai/AiWorkspaceController` `/ai`**：`knowledge-bases`(CRUD/documents/qa-pairs/share/usage/stats)、`conversations`(messages/feedback)、`knowledge-ingest-tasks/{taskId}`、`notes`(CRUD/public/tags；**2026-07-16 起审核流**：create/update 的 status 白名单仅 draft/published，普通用户请求 published 强制落 pending 并通知超管，管理员免审；已发布笔记标题/正文被普通用户改动自动打回 pending 重审)。~~agents/plugins 全部端点~~ 已于 2026-07-12 裁撤
**打卡 `checkin/CheckinController` `/checkin`**：`POST|GET challenges`、`GET|PUT|DELETE challenges/{id}`、`POST challenges/{id}/checkin`、`GET challenges/{id}/records|leaderboard`、`POST records/{id}/share`
**问答 `qa/QaController` `/qa`**：`GET {postId}`、`POST {postId}/accept/{commentId}`
**私信 `message/MessageController` `/messages`**：`POST`、`GET conversations`、`GET conversations/{peerId}`、`PUT conversations/{peerId}/read`、`GET unread-count`、`PUT read-all`
**通知 `notify/NotifyController` `/notifications`**：`GET`、`GET unread-count`、`PUT {id}/read`、`PUT read-all`、`PUT batch-read`
**关注 `follow/FollowController` `/follows`**：`POST|DELETE {followeeId}`、`GET check/{targetId}`、`GET {userId}/followers|following|counts`
**举报 `report/ReportController` `/reports`**：`POST`
**成就 `achievement/AchievementController` `/achievements`**：`GET`
**搜索 `search/SearchController` `/search`**：`GET`（type=POST/USER/RESOURCE/SPACE，四类均 Meili 优先 + MySQL LIKE 兜底；`POST /admin/search/reindex` 全量重建 4 个索引，返回分类计数）
**学习 `learning/LearningController` `/api/v1/learning`**：`GET tutorials`（教程列表）、`GET tutorials/{id}`（教程详情+章节目录）、`GET lessons/{id}`（章节正文，内容优先从 OSS 读取，DB `content` 列兜底）（2026-06-29 新增）；**管理端排序**：`PUT /admin/learning/tutorials/reorder`、`PUT /admin/learning/notes/reorder`（`tenant:learning:manage`，2026-07-11 补录）
**笔记公开 API**：`GET /api/v1/ai/notes/public`（已发布笔记列表）、`GET /api/v1/ai/notes/public/{id}`（单篇详情，含阅读计数；2026-07-16 起非 published 仅作者本人/管理员可见——供审核预览与作者自查，且此场景不计阅读数，匿名与他人一律 40400）（2026-06-29 新增）
**AI 限流状态**：`GET /api/v1/ai/rate-limit-status`（返回用户各级剩余次数）（2026-06-30 新增）
**教程抓取（管理）**：`POST /api/v1/admin/learning/crawl`（`@SaCheckPermission("tenant:dashboard")`，同步菜鸟教程）（2026-06-29 新增）
**租户信息 `tenant/TenantInfoController`**：`GET /tenant/info`（游客可访问，前端按此渲染租户皮肤）
**系统公告 `announcement/AnnouncementController` `/announcements`**：`GET active`（当前生效公告，用于顶栏 banner，最多 10 条）、`GET /`（分页列表，含 page/size 查询参数）、`GET {id}`（详情，含 markdown 正文）（2026-07-05 新增）

**管理后台 `/admin/**`（需 TENANT_ADMIN / SUPER_ADMIN）**：
- `admin/AdminController` `GET /admin/dashboard`（返回真实 count + 7 天趋势 + 空间分类分布 + 最近 5 条审计）
- `admin/AdminUserController` `/admin/users`：`GET`、`PUT {id}/role|ban|unban`、`PUT batch-status`
- `admin/AdminPostController` `/admin/posts`：`GET`（`?trash=true&page=1&size=20` 返回 `PageResult<PostVO>`）、`PUT {id}/pin|essence|status`、`DELETE {id}`、`PUT {id}/restore`、`DELETE {id}/purge`；**批量**：`PUT batch-status`、`DELETE batch`、`PUT batch-restore`、`DELETE batch-purge`
- `admin/AdminSpaceController` `/admin/spaces`：`GET`（同上分页）、`PUT {id}/status`、`DELETE {id}`、`PUT {id}/restore`、`DELETE {id}/purge`；**批量**：`PUT batch-status`、`DELETE batch`、`PUT batch-restore`、`DELETE batch-purge`
- `admin/AdminResourceController` `/admin/resources`（2026-07-02 分页+批量；2026-07-13 审核流）：`GET`（分页，status 筛选支持 0/1/2/3）、`PUT {id}/approve`（通过并发布+站内通知+入索引）、`PUT {id}/reject`（body `{reason}` 必填，驳回+通知带原因）、`PUT batch-approve`（body `{ids}`，返回 `{success, failed:[id]}`）、`PUT {id}/status`、`DELETE {id}`、`PUT {id}/restore`、`DELETE {id}/purge`；**批量**：`PUT batch-status`、`DELETE batch`、`PUT batch-restore`、`DELETE batch-purge`
- `admin/AdminNoteController` `/admin/notes`（AiNote String id）：`GET`（分页 `PageResult<Map>`）、`PUT {id}/status`（draft/pending/published/rejected/hidden）、**审核**（2026-07-16）：`PUT {id}/approve`、`PUT {id}/reject`（body `{reason}` 必填）、`PUT batch-approve`（body `ids: string[]`，返回 `{success, failed}`）、`DELETE {id}`、`PUT {id}/restore`、`DELETE {id}/purge`；**批量**：`PUT batch-status`、`DELETE batch`、`PUT batch-restore`、`DELETE batch-purge`（body `ids: string[]`）；**外部同步**：`POST sync-external` body `{source, rootUrl, recursive?, sourceName, sourceAuthor, tags[], ownerId}` → `{synced, failed:[{url,reason}], noteIds[]}`（走 `NoteSyncService` + `NOTE_SYNC_PROXY_HOST/PORT` 代理 + `note-sync.allowed-hosts` 白名单）
- `admin/AdminCheckinController` `/admin/checkin/challenges`：`GET`（分页）、`PUT {id}/status`（1 正常 / 2 隐藏）、`DELETE {id}`、`PUT {id}/restore`、`DELETE {id}/purge`；**批量**：`PUT batch-status`、`DELETE batch`、`PUT batch-restore`、`DELETE batch-purge`
- `admin/AdminCommentController` `/admin/comments`：`GET`（分页）、`PUT {id}/status`（1/0）、`DELETE {id}`、`PUT {id}/restore`、`DELETE {id}/purge`；**批量**：`PUT batch-status`、`DELETE batch`、`PUT batch-restore`、`DELETE batch-purge`
- `admin/AdminAnnouncementController` `/admin/announcements`（2026-07-05 新增）：`GET`（分页 `keyword/status/level/trash/page/size`）、`POST`（新建）、`PUT {id}`（编辑）、`PUT {id}/status`（draft/published/archived）、`PUT {id}/pin`（切置顶 toggle）、`DELETE {id}`、`PUT {id}/restore`、`DELETE {id}/purge`；**批量**：`PUT batch-status`、`DELETE batch`、`PUT batch-restore`、`DELETE batch-purge`。权限 `tenant:announcement:manage`，`BatchAnnouncementStatusRequest` 状态取值 draft/published/archived。

**批量端点规范**：body `{ids: number[] (或 string[] for notes), status?: number|string}`，DTO 位于 `admin/dto/Batch*Request.java`，`@Size(max=100)` 每次上限 100 条；`batch-purge` 返回 `{success, failed:[{id,reason}], counts:{...}}`（循环独立事务，单条失败不影响整批），其余 batch 端点返回 `Integer`（影响行数）。分页参数 `page`（默认 1）+ `size`（默认 20，上限 100），返回 `PageResult<T>{items,total,page,size,pages}`（`admin/dto/PageResult.java`）。
- `admin/AdminAuditLogController` `GET /admin/audit-logs`
- `admin/export/ExportController` `/admin/export`：`POST users|posts|audit_logs|reports`（XLSX，1/min 限流）。**细粒度权限**：每个端点独立权限点 `tenant:export:{users|posts|audit|reports}`；`fullPii=true` 完整 PII 导出**仅 SUPER_ADMIN**（TENANT_ADMIN 即使有导出权限也被 `enforceFullPiiPermission` 拒绝，漏洞13/T8.6 加固）
- `report/AdminReportController` `/admin/reports`：`GET`、`PUT {id}/handle`、`PUT batch-handle`
- `sensitive/SensitiveWordController` `/admin/sensitive-words`：`GET|POST`、`DELETE {id}`
- `search/SearchReindexController` `POST /admin/search/reindex`
- `tenant/TenantController` `/admin/tenants`：`GET|POST`、`PUT {id}`、`PUT {id}/status`（均 SUPER_ADMIN）；`GET|PUT {id}/ai-config`（TENANT_ADMIN 允许操作自己租户，SUPER_ADMIN 任意租户）

---

## 8. 数据库（`db/`）

- **生产数据库**：本机 Docker 容器 `deploy-mysql-1`（mysql:8.0），通过 compose 网络内 `mysql:3306` 访问，应用容器 JDBC URL 含 `characterEncoding=UTF-8`。**可直接 `docker exec deploy-mysql-1 mysql ... campus_forum` 直连管理，无需 SSH 跳板机。**
- **字符集**：32 张表为 `utf8mb4_0900_ai_ci`，**3 张表例外**为 `utf8mb4_unicode_ci`（`ai_notes` / `learning_tutorials` / `learning_lessons`，2026-07-11 实测）——跨表 JOIN 时注意排序规则冲突风险。仅 `tenants` 表的初始种子数据（V1 bootstrap）曾出现 latin1/cp1252 双重编码乱码，已于同日通过 `V20260624_01__fix_tenant_charset.sql` 修复。Java 应用通过 `characterEncoding=UTF-8` 写入的所有数据（users/posts/spaces 等）始终正确。
- **`db/schema.sql`**（~17KB）：完整建表脚本，docker-compose 挂载到 MySQL initdb 自动执行。
- **`db/migrations/`**：手工编号迁移，命名兼容 Flyway（`V<date>_<seq>__desc.sql`），与 classpath `backend/src/main/resources/db/migration/` 双向同步（各 29 个文件；2026-07-13 新增 `V20260713_01__resource_review`：resources 表 status 默认改 2 + review_reason/reviewed_by/reviewed_at 三列 + (tenant_id,status) 索引；2026-07-16 新增 `V20260716_01__ai_notes_review`：ai_notes 加 review_reason/reviewed_by/reviewed_at 三列，status 为 VARCHAR 直接扩值无需改列）。**已引入 Flyway 自动执行**（`spring.flyway`），新迁移放入 classpath 后应用启动时自动运行。存量数据库通过 `baseline-on-migrate=true` + **`baseline-version=20260624_02`**（2026-07-11 核实，非旧文档所写 _01）平滑过渡——baseline **之前的全部 14 个历史迁移在生产库从未被 Flyway 执行**。
- ✅ **schema.sql 缺表陷阱已修复（2026-07-12）**：`post_ai_cards` 建表（合并 3 个早于 baseline 的迁移的最终结构）与 `users` 表第三方登录列（`wechat_openid/wechat_unionid/qq_openid/github_id` + 3 个唯一键）已补进 `db/schema.sql`，全新部署不再缺表缺列。`points_logs` 表在生产库残留不变（DROP 迁移早于 baseline 从未执行，schema.sql 本就没有它，无碍）。
- **33 张表**（2026-07-12 裁撤 agents/plugins 3 张、新增 ai_kb_chunks 1 张后）：`tenants` `users` `posts` `comments` `reactions` `spaces` `space_members` `resources` `messages` `notifications` `follows` `reports` `checkin_challenges` `checkin_records` `qa_questions` `achievements` `user_achievements` `sensitive_words` `audit_logs` `post_ai_cards` `points_logs`（积分系统已移除，表仍存在）+ **AI 工作台 8 张**：`ai_knowledge_bases` `ai_kb_documents` `ai_kb_qa_pairs` `ai_kb_chunks`（2026-07-12，RAG 切块） `ai_ingest_tasks` `ai_conversations` `ai_messages` `ai_user_favorites`（~~ai_agents/ai_plugins/ai_user_installed_plugins~~ 已 DROP）+ **笔记** `ai_notes`（2026-06-28）+ **学习教程** `learning_tutorials` `learning_lessons`（2026-06-29，全局表无 tenant_id，加入 TENANT_IGNORE_TABLES）+ **系统公告** `announcements`（2026-07-05，多租户）。
- 软删除：`deleted` 字段（MyBatis-Plus 逻辑删除，0/1）——**并非全部表适用**：`messages`（私信不可恢复）、`reactions`、`qa_questions`、`checkin_records`、`notifications`、`follows`、`reports`、`audit_logs`、`user_achievements` 等表**无 `deleted` 列**。
- **`TENANT_IGNORE_TABLES` 完整清单（10 项，`MyBatisPlusConfig`）**：`tenants`、`achievements`（无 tenant_id 列的全局字典）、AI 工作台 6 张子表（`ai_kb_documents/ai_kb_qa_pairs/ai_ingest_tasks/ai_kb_chunks/ai_messages/ai_user_favorites`，租户归属由父表间接决定；2026-07-12 移除已裁撤的 ai_user_installed_plugins、加入 ai_kb_chunks）、`learning_tutorials`/`learning_lessons`。

> **给 AI 的提醒**：改表结构时要**同时**改 `schema.sql`（新部署用）**和**新增一个 `migrations/V<date>__*.sql`（存量升级用），两者别只改一个。生产数据库本机可达，**不要假设它在远程 VM**，直接连容器诊断。

---

## 9. 前端

- **入口** `src/main.ts`：注册 Pinia / Router / i18n。含一段**启动重定向** `getBootRedirectTarget()`：已登录非游客访问 `/` 跳 `/resources`。（遗留的 legacy 路径重定向已删除，由 router 覆盖。）
- **路由** `src/router/index.ts`：`createWebHistory`（HTML5 history，需 nginx `try_files` 兜底）。`beforeEach` 守卫读 `localStorage.{token,role}`，按 `meta.{requiresAuth,guest,requiresAdmin}` 控制；`GUEST` 角色仅允许 `/ /resources /login /register /forgot-password /announcements(+详情)` + 帖子/资源详情。
  - ⚠ `/tools` `/software` 路由仍存在但指向占位页，已从导航栏移除（2026-06-28）。
  - ⚠ **"空间"产品线前端保留但入口收敛（2026-07-12 用户确认：因备案原因暂时无法对外服务，代码不动）**：前端路由 `/square`、`/spaces` 列表页 redirect 到 `/learning`（nginx 同款 302 保留），`Square.vue`/`Spaces.vue`/`SpaceCreate.vue` 暂为孤儿代码；`SpaceDetail.vue` 路由 `/spaces/:id` 可达（直链/刷新已不再被 nginx 劫持，2026-07-12 修复），仅搜索结果可跳转。后端 space 全套 API 正常。
  - ⚠ **`/ai/libraries/:id`**（KnowledgeBaseDetail.vue 知识库详情页）此前未记录，2026-07-11 补录。
  - `/checkin` — 打卡页面（`CheckinChallenges.vue`），移除假数据，接入真实后端 API（2026-06-29）。
  - `/learning` — 学习页（`Learning.vue`），含精选教程网格 + 公开笔记列表（2026-06-29）。
  - `/learning/tutorial/:id` — 教程阅读器（`TutorialReader.vue`），三栏布局（章节目录/正文/右侧AI），内容抓取自菜鸟教程（2026-06-30）。
  - `/learning/notes/:id` — 公开笔记详情页（`NotePublicDetail.vue`），只读模式+右侧AI（2026-06-29）。
  - `ai` 重定向到 `/ai/chat`。AI 页面路由：`/ai/chat`（AiChat.vue 对话+模型选择）、`/ai/libraries`（KnowledgeLibraries.vue 知识库管理）、`/ai/notes`（KnowledgeNotes.vue 笔记编辑器）。左侧导航 KnowledgeSidebar 含品牌入口+用户头像下拉。所有 AI 页面从 `前端/pages/knowledge/` 静态 HTML 转换为 Vue 组件（2026-06-28）。
- **API 层** `src/api/*.ts`：每个后端模块一个文件（`auth/users/posts/comments/spaces/resources/ai/ai-workspace/learning/checkin/qa/messages/notifications/follows/report/achievement/search/admin/tenants/announcements`）。统一走 `request.ts`：
  - `baseURL='/api/v1'`，token 放 `Authorization` 头（**不带 `Bearer` 前缀**），`GUEST_TOKEN` 不发送。
  - **前端不再注入 `X-Tenant-Id`**（租户由服务端权威解析）。
  - 响应拦截：`code!==0` reject；401 清登录态跳 `/login`；429/415/413 转友好文案。
- **状态** `src/stores/auth.ts`：唯一 Pinia store，管理登录态（token/role/tenant/user）。
- **Composables** `useTheme.ts`（主题）、`useWebSocket.ts`（实时通知）。
- **Utils**：`resource-preview.ts`（资源预览类型判断，新增）、`resource-topic.ts`、`mention.ts`（@提及）、`authValidation.ts`、`clipboard.ts`、`render-icon.ts`。部分带 `.test.ts`（vitest）。
- **i18n** `locales/`：`zh-CN.json` / `en-US.json`。
- **PWA** `public/`：`manifest.webmanifest` + `sw.js` + `registerSW.js`（vite-plugin-pwa 生成，离线缓存）。
- **WebSocket** `useWebSocket.ts`：全局单连接，先 `POST /auth/ws-ticket` 拿 30s 票据，再连 `/ws/notify?ticket=...`（**用浏览器原生 `WebSocket`**，票据避免主 token 泄漏到 access log）。后端 `TenantHandshakeInterceptor` 握手期校验票据并绑定 userId/tenantId。游客（`GUEST_TOKEN`）不连接。
  - 注：早期 `package.json` 曾装 `socket.io-client` 但从未使用，已于 2026-06-24 移除（实时通知一直用原生 `WebSocket`）。

---

## 10. 部署（`deploy/`）—— 基于生产服务器实际状态（2026-06-24）

### 10.1 运行中的容器（7 个）
| 容器名 | 镜像 | 用途 | 来源 |
|---|---|---|---|
| `deploy-nginx-1` | nginx:1.27-alpine | 反向代理，:80 | compose |
| `deploy-app-1` | deploy-app (自建) | Spring Boot，`127.0.0.1:8080` | compose |
| `deploy-mysql-1` | mysql:8.0 | 数据库，容器网络内 :3306 | compose |
| `deploy-redis-1` | redis:7-alpine | 缓存 + Sa-Token 持久化 | compose |
| `deploy-meilisearch-1` | getmeili/meilisearch:v1.9 | 搜索引擎 | compose |
| `deploy-minio-1` ⚠ | minio/minio:latest | **孤儿容器**（旧 compose 版本遗留，app 已不使用） | compose 旧版 |

> **关键**：compose 当前只定义 5 个服务（nginx, app, mysql, redis, meilisearch）。`deploy-minio-1` 不在 compose 中，是之前版本的遗留容器，`docker compose down` 不会自动清理。2026-07-11 实测运行容器共 **6 个**（上表 5 服务 + minio 孤儿）；旧文档提到的 `my-claw` 容器已不存在（1Panel 主进程 `/usr/bin/1panel` 仍在运行，非容器化）。

### 10.2 生产配置事实
- **Profile**: `SPRING_PROFILES_ACTIVE=prod`
- **存储**: `STORAGE_TYPE=oss`，阿里云 OSS 北京 region（`oss-cn-beijing.aliyuncs.com`），bucket `mybucket01-qingyunge`
- **AI**: **真实可用**（2026-07-11 核实）——租户 1 `ai_config` 配 DeepSeek（openai 协议 + AES-GCM 加密 key），`.env` 配 `MIMO_API_KEY`（mimo 系模型）；`AI_PROVIDER=mock` 为死配置（代码不读 `ai.provider`，勿据此判断未启用），`AI_MODEL` 已是 `deepseek-v4-flash`，详见 §5.4
- **搜索**: MeiliSearch，host `http://meilisearch:7700`
- **邮件**: QQ SMTP（`smtp.qq.com:465`，SSL），发件 `morose_haha@qq.com`
- **微信登录**: 小程序 AppID `wx82cec5c8d9e51ecc`，已配置
- **站点**: 域名 `qingyunsq.top` / `www.qingyunsq.top`（nginx 443 HTTPS + 80→301，域名硬编码于 nginx.conf），标题「小青知识库」
- **Office 预览**: **实际不可用**——kkfileview 服务在 compose 中整体注释禁用，`.env` 的 `OFFICE_PREVIEW_URL` 指向无服务监听的 localhost:8012，前端降级为"下载查看"
- **1Panel**: 管理面板端口 34813，托管此服务器运维

### 10.3 关键配置文件
- **`deploy/.env`**：生产环境变量（**未被 git 追踪**，含真实 OSS/SMTP/微信凭据）。`deploy/.env.example` 是模板。
- **`deploy/docker-compose.yml`**：5 服务定义。`app` 固定 `SPRING_PROFILES_ACTIVE=prod`，仅暴露 `127.0.0.1:8080`。`mysql` 无显式 `--character-set-server` 参数（但全部表已是 utf8mb4）。
- **`backend/src/main/resources/application-prod.yml`**：**严禁任何 `${VAR:default}` 字面密钥兜底**，所有密钥缺失即启动失败。
- **`deploy/nginx/nginx.conf`**：屏蔽 actuator（仅放行内网 prometheus）、swagger 路径、隐藏文件；`client_max_body_size 60m`；`try_files` SPA fallback。
- **`deploy/SECURITY.md`**：**生产部署前必读**。

---

## 11. 常用命令

```bash
# 后端
cd backend
mvn test                 # 跑测试（surefire 注入测试用 crypto key）
mvn spring-boot:run      # 本地启动（默认 dev profile）

# 前端
cd frontend
npm ci
npm run lint             # eslint
npm run test             # vitest
npm run build            # vue-tsc 类型检查 + vite build
npm run dev              # 开发服务器(:3000，代理 /api /ws /uploads → :8080)

# 部署
cd deploy
cp .env.example .env     # 按 SECURITY.md 填写所有必填项
bash install.sh

# 仓库同步（当前主仓库是 qingyunsq，不是 origin）
git push qingyunsq main   # → https://github.com/zhh123465/qingyunsq（public）
git push origin main      # → https://github.com/zhh123465/campus（旧仓库，可选）
```

---

## 12. 给 AI 助手的关键约定与陷阱（务必遵守）

1. **认证是 Sa-Token tik 随机 token，不是 JWT**。不要新增 `JWT_SECRET` / `SA_TOKEN_JWT_SECRET_KEY` 等死配置。token 安全实际依赖 Redis 凭证（`REDIS_PASSWORD`）。
2. **配置覆盖链**：`application.yml`（默认 `active: dev`，含开发用弱默认值）← `application-dev.yml`（dev 专用默认）/ `application-prod.yml`（prod，无字面密钥）。改默认值前先确认对哪个 profile 生效。
3. **租户隔离靠 `TenantContext`(ThreadLocal) + MyBatis-Plus 插件自动注入 `tenant_id`**。跨线程务必手动传递；访问 session 数值字段按 `Number` 取值。
4. **生产存储为阿里云 OSS**（`storage.type=oss`，北京 region）。源码中无 MinIO 实现，Local 仅 dev/test。服务端有 `deploy-minio-1` 孤儿容器（旧 compose 版本遗留），app 不使用。
5. **安全加固有完整体系**：大量代码注释引用 `bugfix.md` 漏洞编号 + spec 任务号（如 T7.1）。改安全相关代码前先理解原意，别误删防御逻辑（SSRF 校验、签名 URL、MIME 黑名单、限流敏感路径保护、启动校验）。
6. **改表 = 改 `schema.sql` + 加 `migrations/V*.sql` 到 classpath**（`backend/src/main/resources/db/migration/`）。应用启动时 Flyway 自动执行新迁移；已对存量生产库配置 baseline 跳过历史迁移。
7. **API 文档生产默认关闭**（`SPRINGDOC_ENABLED=false` + DocAccessFilter + nginx 三重防护），不要为了调试在 prod 打开。
8. **前端 token 无 `Bearer` 前缀**，存 `localStorage.token`；前端不注入租户头。
9. 历史演进：**积分系统已移除**（别再引用 points 相关表/接口；`points_logs` 表在生产库残留只因 DROP 迁移早于 Flyway baseline 从未执行）。**微信小程序登录已真实可用**（`wechat-login`，首登自动建号 + 播种欢迎知识库，唯一键 `(tenant_id, wechat_openid)`）。**GitHub 登录已接通**（2026-07-12，授权码流程：`GET /auth/github/authorize-url` → GitHub 授权 → 302 回 `/login?code=..&state=..` → `POST /auth/github-login`；state 存 Redis 10 分钟防 CSRF；首登按 `(tenant_id, github_id)` 自动建号；**生效前提：`.env` 填 `GITHUB_CLIENT_ID/SECRET`**，GitHub OAuth App 的 callback 必须是 `https://www.qingyunsq.top/login`；海外出站走 `SOCIAL_PROXY_HOST/PORT`=host.docker.internal:7890）。**QQ 登录仍是无接线骨架**（`QqOAuthClient` 无 Controller，前端按钮提示"暂未开放"）。**智能体/插件市场已整体裁撤**（2026-07-12，勿再引用 /ai/agents、/ai/plugins）。
10. **生产服务器**：Docker Compose 部署于 `/root/projects/campus/deploy/`，`docker compose` 命令可用。**MySQL 容器本机可达**（`docker exec deploy-mysql-1 mysql ...`），诊断数据问题直接连容器，不要假设数据库在远程。`deploy/.env` 含真实凭据，勿提交到 git；`deploy/nginx/ssl/` 含 qingyunsq.top 的 TLS **私钥**，两个仓库都是 **public**，已由 `.gitignore` 拦截（`deploy/nginx/ssl/`、`*.pem`、`*.key`）。
11. **1Panel**（端口 34813）管理本服务器运维，`1panel*`/`quick_start.sh`/`*.tar.gz` 已被 `.gitignore` 拦截防止误提交（`quick_start.sh` 是 1Panel 官方安装脚本，不是本项目脚本），但不要随意删除 1Panel 安装目录或停止其进程。
12. `rg` 不可用时用 `grep`/`find`；工作区常有未提交改动，**不要回滚不是你造成的改动**。
13. **GitHub 出站必须走代理**（2026-10-08 实测）：直连 SSH 22 出海被限速到 ~100KiB/s（推 14MB 要数分钟），且本地 mihomo 节点**屏蔽 22 端口**（`nc -X connect` 到 :22 超时）。已在 `~/.ssh/config` 把 `github.com` 指到 GitHub 官方 443 入口：`HostName ssh.github.com` + `Port 443` + `ProxyCommand nc -X connect -x 127.0.0.1:7890 %h %p`（`ls-remote` 从数十秒降到 3 秒）。⚠️ mihomo 未运行时 GitHub 的 SSH 操作会全部失败，临时走直连用 `git -c core.sshCommand='ssh -p 22' ...`。容器内出站另有 `SOCIAL_PROXY_HOST/PORT` 与 `deploy/proxy-forwarder.py`（宿主 7890 → 容器可见的 7891）。

---

## 13. 仓库与工作区状态（截至 2026-10-08）

> 本节记录**仓库同步状态**；历史工作区改动流水见 §14.2「近期已解决」。

**仓库位置（两个都是 public 仓库）**
- 本地工作区：`/root/projects/campus`（目录名沿用旧名，未随仓库更名）
- `origin` = `git@github.com:zhh123465/campus.git` —— 旧仓库，`branch.main.remote=origin` 仍指向它
- `qingyunsq` = `git@github.com:zhh123465/qingyunsq.git` —— **当前主仓库**，日常推送走这个 remote
- ⚠️ 仓库公开，`.env` / TLS 私钥 / 含真实姓名的材料都不得入库（`.gitignore` 已拦截，见下）

**2026-10-08 全量同步（提交 f5e494d，强推至 `qingyunsq/main`）**
- 积压的全部工作区改动（293 文件，+31068/-19088）一次性提交：公告模块、learning 学习模块、AI 工作区、5 个 `AdminXxxController`、资源审核、GitHub 登录接通、搜索索引改直连（删 `SearchIndexEvent`/`SearchSyncListener`）、PWA 由 `vite-plugin-pwa` 改为自写 `sw.js`/`registerSW.js`
- **`backend/src/main/resources/db/migration/` 29 个迁移首次入库**——此前只存在于 `db/migrations/` 手工镜像、从未被 git 跟踪，而 `flyway.locations=classpath:db/migration`，新克隆的仓库缺这套脚本无法自动迁移（两目录现已 29 vs 29 一致，改表仍须两边同步）
- `.gitignore` 新增排除：`deploy/nginx/ssl/`（qingyunsq.top 的 **TLS 私钥**）、`/quick_start.sh`（实为 1Panel 安装脚本，非项目脚本）、根目录 `/*.png` `/*.pdf`（临时截图 + 大创申报/结题材料，含真实姓名）
- 未入库但保留在磁盘：根目录 3 个 PDF、2 张调试截图、`1panel-v1.10.34-lts-linux-amd64/`（135MB）
- ⚠️ `campus` 仓库未推（仍停在 a520f56），需要时另行 `git push origin main`

**验证状态**：`mvn -o compile` 通过、`npm run build`（含 vue-tsc 类型检查）通过；**未跑 `mvn test`**（需 Docker/Testcontainers）。

---

## 14. 已知问题与改进计划（TODO 跟踪）

> 本节是项目已知问题与改进方案的**单一跟踪入口**。修复某项后，请把它移到文末「已解决」并注明日期。

### 14.0 全模块核实新发现（2026-07-11 发现，2026-07-12 大部分已处置）

1. ✅ ~~schema.sql 缺 `post_ai_cards` 建表~~ → 2026-07-12 已补进 schema.sql（合并 3 个迁移的最终结构）。
2. ✅ ~~nginx 302 重定向劫持活跃 SPA 路由~~ → 2026-07-12 已删除 `/checkin*` 与 `^~ /spaces/` 的 302（保留 `/square*` 与 `= /spaces` 列表页迁移），线上验证 `/checkin`、`/checkin/1`、`/spaces/3` 均 200。
3. ✅ ~~跨实例 WebSocket 广播未接线~~ → 2026-07-12 已接线：NotifyService/MessageService/CommentService 三处推送全部改走 `WebSocketBroadcaster.broadcast()`（Redis pub/sub，自身实例经订阅回投，Redis 故障降级本地直投）。
4. ✅ ~~搜索事件驱动链路死代码~~ → 2026-07-12 已删除 `SearchSyncListener`/`SearchIndexEvent`（被 `SearchIndexService` 直接同步范式取代）。
5. ✅ ~~users 表第三方登录列未同步 schema.sql~~ → 2026-07-12 已补列 + 3 个唯一键。
6. 🟡 **举报内容未经 HTML 净化入库**（`ReportService.create` 不走 sanitizer，本域唯一例外；仅管理端渲染，风险面小但需知情）——仍待处置。
7. 🟢 无效环境变量残留：生产 `.env` 的 `APP_CRYPTO_KEY`（compose 不透传、代码零引用——真正的加密主密钥是 `CRYPTO_MASTER_KEY`）与 `MINIO_ROOT_USER/PASSWORD` 等 minio 遗留项——仍待处置。
8. 🟢 `deploy/SECURITY.md` §8/§9 的"手动执行迁移 SQL"SOP 早于 Flyway 集成，未更新为"已自动化"——仍待处置。
9. 🟢 `OssStorageService.PUBLIC_URL_TTL_MILLIS` 硬编码 5 分钟，与接口 javadoc 声称的"signedUrlTtlSeconds×5"公式不符（代码内部矛盾）——仍待处置。

### 14.1 待解决（按建议优先级）

#### 🔴 ~~P1 — AI 工作台改为数据库持久化（原检查报告 O3）~~ ✅ 已在 2026-06-24 完成

#### 🟡 P2 — 前端占位路由（仅 `FeaturePlaceholder.vue` 占位页未实现）
- **已完成**：`/tools`、`/software` 已从导航栏移除（2026-06-28）。`/learning` 已实现（2026-06-29）。`/checkin` 假数据已替换（2026-06-29）。仅剩 `/tools`、`/software` 路由保留但不展示。
- ~~**新的 TODO**：AI 服务当前为 mock 模式，切换真实服务需配置 API key。~~ ✅ 已失效：AI 实际已真实可用（租户 ai_config + MIMO_API_KEY，2026-07-11 核实，见 §5.4）。
- **剩余**：`/learning` 路由仍指向 `FeaturePlaceholder.vue` 占位页（功能未实现），属产品节奏问题，按规划推进即可。

### 14.2 近期已解决（2026-06-24 ~ 2026-07-16，Claude Code）

- ✅ **笔记发布审核流（对齐资源审核）**（2026-07-16，用户报 bug：发布笔记未经审核直接公开可见）：
  - **根因**：`AiWorkspaceService.createNote/updateNote` 直接透传 body 里的 `status`，用户点"发布"即 `published` 立即在学习页公开。
  - **状态模型**：`ai_notes.status`（VARCHAR）扩展 `pending`（待审核）/`rejected`（已驳回），全集 `draft/pending/published/rejected/hidden`；加 `review_reason/reviewed_by/reviewed_at` 三列（V20260716_01，schema.sql 同步并顺手补上遗漏的 `content_oss_key` 列——V20260630_02 加的但 schema.sql 一直没同步）。存量 published 视为已通过不回填。
  - **提交侧**：create/update 的 status 白名单仅 `draft/published`（非法值 40000）；普通用户请求 published 强制落 `pending`，TENANT_ADMIN/SUPER_ADMIN 免审（`currentRoleOrNull()` 从 Sa-Token session 读 role，与 ResourceService 同款）。**编辑重审**（用户选定的严格策略）：已发布笔记标题/正文被普通用户改动自动打回 pending（正文对比先 `resolveNoteContent` 读旧 OSS 内容，读失败按"已变化"处理宁可多审）；原样保存/仅改 tags 不打回；rejected 重投时清空 `review_reason`；仅状态由非 pending → pending 时发通知（反复保存不轰炸）。
  - **通知**：新组件 `ai/workspace/service/NoteReviewNotifier`（照 ResourceReviewNotifier 同款范式）——落 pending 后站内通知（type=NOTE_REVIEW）全部 SUPER_ADMIN + 邮件提醒（Redis `note_review_mail:tenant:{id}` setIfAbsent 节流 10 分钟一封，**与资源审核的 throttle key 相互独立**；标题 HtmlEscape；过滤 `.local` 假邮箱；SMTP 在 @Async 经 selfProxy）；审核结果站内通知作者（approve 跳 `/learning/notes/{id}`，reject 附原因跳 `/ai/notes`）。
  - **审核端点**：`AdminNoteController` 加 `PUT {id}/approve`（幂等）/`PUT {id}/reject`（reason 必填截 255）/`PUT batch-approve`（selfProxy 循环单条，返回 `{success,failed}`），`tenant:note:manage` 权限 + 审计（NOTE_APPROVE/NOTE_REJECT/NOTE_BATCH_APPROVE）；`adminSetNoteStatus` 白名单扩为 5 状态。
  - **可见性**：`listPublicNotes` 只放 published 不变；`getPublicNote` 收口为非 published 仅作者/管理员可见（管理端"预览"按钮与作者自查需要，此场景不计阅读数），匿名/他人 40400。KbRagService 检索本人笔记不看 status、NoteSyncService 管理员同步直接 published，均不受影响。
  - **前端**：`KnowledgeNotes.vue` 发布按钮响应按实际落库状态提示"已提交审核"、状态标签/筛选覆盖 5 状态（此前 pending 会被误显示为"草稿"）、rejected 显示驳回原因横幅、普通保存时 pending→回传 published / rejected|hidden→回传 draft（避开白名单 400）；`AdminNotes.vue` 照 AdminResources 加待审/驳回筛选、行内通过/驳回（Modal 必填原因）、批量通过、驳回原因 tooltip；`api/admin.ts` 加 approveNote/rejectNote/batchApproveNotes。
  - **部署验证**（2026-07-15/16 上线）：Flyway `20260716.01` 生产应用成功、启动无 ERROR；匿名回归——公开列表/详情正常（存量 18 篇 published 不受影响）、draft 详情匿名 40400、匿名调 approve 40100；登录态全链路（发布→pending→通知/邮件→approve/reject）待用户自测或另行授权代跑。

- ✅ **资源上传审核流 + 任意文件类型 + 邮件提醒**（2026-07-13，用户指派）：
  - **状态模型**：resources.status 扩展为 `0=隐藏 1=已发布 2=待审核 3=已驳回`（列默认改 2），加 `review_reason/reviewed_by/reviewed_at` 三列 + `(tenant_id,status)` 索引（V20260713_01，schema.sql 同步）。普通用户上传落 2，TENANT_ADMIN/SUPER_ADMIN 上传免审落 1；SHA-256 去重只匹配已发布（status=1）资源，驳回后可重传。
  - **可见性收口（连带安全修复）**：`ResourceService.canAccess` 补 `status != 1 → 仅上传者/管理员`，堵住原缺陷——此前 download/preview/getById 只查 deleted 不查 status，被隐藏的资源可被任何登录用户直链下载。签名直链场景新增 `ensureCanAccessAs(sigUserId)`：sig token 内嵌 userId，按签发人身份校验（否则上传者预览自己的待审资源会被按游客 404）；`verifySignatureOrLogin` 现返回 sigUserId 传给 service 层。
  - **审核端点**：`PUT /admin/resources/{id}/approve|reject`（reject body reason 必填）+ `PUT batch-approve`；approve 幂等（已发布直接 return）、入 Meili 索引、站内通知上传者；reject 记录原因、删索引、通知带原因。均写审计日志。
  - **邮件提醒**：新组件 `resource/service/ResourceReviewNotifier`——上传落待审后站内通知（type=RESOURCE_REVIEW）全部 SUPER_ADMIN + 邮件提醒（Redis `resource_review_mail:tenant:{id}` setIfAbsent 节流 10 分钟一封；正文带待审总数与 `email.site-base-url`/admin/resources 直达链接；收件人过滤 `.local` 占位邮箱即微信/GitHub 假邮箱）。`EmailService` 接口加 `sendNotice(to, subject, html)`（Smtp/Log 双实现）；SMTP 发送在 `@Async`（经 selfProxy 调用，AOP 才生效），收件人与待审计数在调用方线程算好（跨线程丢 TenantContext）。上传主流程 try/catch 包裹，通知失败不影响上传。
  - **任意文件类型**：`security.upload.allow-any-extension`（prod 默认 true，ENV `UPLOAD_ALLOW_ANY_EXTENSION`）——开启后扩展名白名单跳过（blocked-extensions 黑名单配置仍生效）、`MimeTypeValidator` 对未注册扩展名（exe/apk/zip/jar 等）直接放行（**刻意不做 MIME 黑名单**——黑名单含 x-msdownload 等可执行类型，而支持可执行程序正是需求；甄别责任转移人工审核），已注册扩展名仍走 Tika 交叉验证防"PHP 改名 .png"。超长扩展名(>32,VARCHAR 列宽)按无类型处理。下载安全面不变：强制 attachment、可执行类型无在线预览。
  - **大小限制 50MB→200MB**：Spring multipart 200MB/210MB（application.yml base 层）+ nginx `client_max_body_size 210m`。
  - **前端**：Resources.vue（"我的上传"改走 `/resources/mine` 含待审/驳回 + 卡片状态角标 + 上传弹窗提示审核 + 待审资源不混入公开列表）、ResourceUpload.vue / SpaceDetail.vue（同款提示）、ResourceDetail.vue（本人视角待审/驳回/隐藏三色横幅，防空 fileType）、AdminResources.vue（状态筛选 4 值、通过/驳回按钮 + 驳回原因弹窗、批量通过）、Notifications.vue + MainLayout.vue（RESOURCE_REVIEW 类型图标/桌面通知）、api/resources.ts（resourceAccept 置空=接受任意类型、getMyResources）、api/admin.ts（approve/reject/batchApprove）。
  - **鉴权**：`/resources/mine` 加入 SaTokenConfig 敏感 GET 清单（须登录）。
  - ⚠ 生产 SMTP 为 QQ 邮箱（deploy/.env SMTP_* 已配好，无需动）；超管邮箱真实可收信。
  - **部署与验证（2026-07-13）**：后端 package 93MB + 前端 vue-tsc/vite 构建 + `compose up -d --build app`（Flyway v20260713.01 成功，16s 启动）+ `restart nginx`（210m 生效实测）。只读验证通过：resources 新列/默认值 2 落库、匿名列表 200 仅含 7 个已发布、`/resources/mine` 匿名 401、approve 端点 401（鉴权先行）、已发布资源详情匿名 200 回归、公网首页 200、GitHub authorize-url enabled:true。**登录态全链路已实测通过（2026-07-15，用户授权在生产建临时账号）**：注入 Redis 验证码（key `email_code:{tenantId}:register:{email}`）注册两个临时账号（上传者 USER + SQL 提为 TENANT_ADMIN 的审核员，提权须在登录前——role 缓存在 Sa-Token session）→ 上传 .exe 落 status=2、匿名不可见、`/mine` 可见 → 超管（id=1）收站内通知 + **真实 SMTP 邮件送达 QQ 邮箱**（日志 `Notice email sent`）→ 10 分钟内二次上传只有站内通知无第二封邮件（Redis 节流生效）→ 上传者 signed-url 直链（无 Authorization 头）可下载自己待审资源（sigUserId 修复点）→ approve：status=1/留痕/上传者收通知/匿名列表可见；reject：status=3/原因落库/通知带原因/匿名不可见。测试数据已全部清理（users/notifications/知识库+QA/审计日志/resources 物理行/meili users 索引/Redis 节流 key/存储文件），线上回归 0 ERROR。注意：resources 删除是 @TableLogic 逻辑删（deleted=1 进回收站），彻底清除需手工物理 DELETE。

- ✅ **六项修复整批上线**（2026-07-12，用户逐条指派；后端镜像重建 + Flyway v20260712.02 + 前端构建均已部署验证）：
  1. **schema.sql 补齐**：`post_ai_cards` 建表（合并 V20260523_01 + V20260524_01/_02 最终结构）+ `users` 第三方登录 4 列与 3 个唯一键，全新部署不再炸（对应 §14.0 #1/#5）。
  2. **nginx 302 劫持清理**：删 `/checkin*` 两条与 `^~ /spaces/`，保留 `/square*`、`= /spaces`（空间因备案暂不对外，列表入口维持迁移）；验证 `/checkin`、`/checkin/1`、`/spaces/3` 200。**运维陷阱**：nginx.conf 是单文件 bind mount，编辑工具替换 inode 后 `nginx -s reload` 读的还是旧文件，必须 `docker compose restart nginx`。
  3. **WS 跨实例广播接线**：NotifyService / MessageService / CommentService 的 `sessionRegistry.sendToUser` 全部改 `webSocketBroadcaster.broadcast(new BroadcastMessage(userId, type, payload))`；启动日志确认已订阅 `campusforum:ws:broadcast`。3 个单测的 mock 类型同步改为 WebSocketBroadcaster。
  4. **搜索全量接入 MeiliSearch**：新增 `search/service/SearchIndexService`（users/resources/spaces 文档构建 + 按可见性增删索引，users 文档**不含 email/studentNo**，漏洞 9 的关键字守卫保留在 SearchService 入口）；写路径挂钩——UserService（注册/微信注册/资料更新/封禁解封，setter 注入防破坏测试构造器）、ResourceService、SpaceService（含管理端单条与批量操作）；`SearchService` 三类搜索 Meili 优先 + LIKE 兜底；`/admin/search/reindex` 重建 4 索引并返回分类计数。删除死代码 `SearchSyncListener`/`SearchIndexEvent`。⚠ **存量数据需管理员触发一次 reindex 才进入 Meili**（触发前搜索走 LIKE 兜底，功能不受影响）。
  5. **智能体/插件市场整体裁撤**：Controller/Service 的 agents/plugins 代码、3 个实体 + 3 个 Mapper、`AiRateLimitInterceptor` 的 plugins invoke 行、前端 types 的 agentId/pluginIds 字段全删；迁移 `V20260712_01` DROP 3 张表 + 删 ai_conversations/ai_messages 的 agent_id/plugin_ids 列 + 清 favorites 中 type='agent' 行；TENANT_IGNORE_TABLES 同步；欢迎 KB 种子 QA 9→8 条（删 Agent 介绍）。
  6. **GitHub 登录接通**（授权码流程）：`GithubOAuthClient` 从 device flow 骨架改写为 authorize/exchangeCode/getUserInfo（海外 API 经 `social.proxy` = host.docker.internal:7890 出站，固定 GitHub 官方域名无 SSRF 面）；新增 `GithubLoginService`（state 存 Redis 10 分钟一次性消费防 CSRF）、`UserService.loginByGithub`（(tenant_id, github_id) 定位、首登建号，与微信同构）、`AuthController` 两端点、`GithubLoginRequest` DTO；SaToken 白名单加 `/auth/github-login`；前端 Login.vue GitHub 按钮真实接入 + 回调处理（replaceState 清一次性 code 防刷新重复消费）；compose/.env/.env.example/application-prod.yml 配置链齐备。~~待用户~~ **已完成（2026-07-13）**：用户提供 OAuth App 凭据并已填入 `deploy/.env`，app 重建后 `GET /auth/github/authorize-url` 返回 `enabled:true` 且授权 URL 正确，GitHub 登录线上可用。
  7. **知识库真实 RAG**（伴随 #5 重写 `buildConvContext`）：新表 `ai_kb_chunks`（V20260712_02）+ `KbRagService`——上传时抽正文（**新依赖 pdfbox 2.0.31**；docx/pptx/xlsx 用 POI、html 用 jsoup、纯文本直读）按段落切块入库，`vectorCount`=真实切块数，ingest task 带真实解析信息；对话时按 query 分词（ASCII 词 + 中文 2-gram）词频打分，检索挂载 KB 的切块 top-6 + QA 对 top-4 + 本人笔记 top-2（标题命中→OSS 拉正文）+ 公开教程章节 top-2，拼 ≤6000 字上下文注入 prompt；仅允许检索 owner 本人或 shared 的 KB。文档删除/KB 删除级联清理切块并回补 vectorCount。⚠ 旧文档（重写前上传的）无切块，需删除重传才可被检索。

- ✅ **欢迎知识库改为一次性种植**（2026-07-10）：
  - **动机**：老逻辑（`UserService.seedWelcomeKnowledgeBase`）每次登录都自检"用户有没有任何 KB"，无则补种。用户如果主动删掉"AI知识库使用指南"，下次登录会被再种回来，形成骚扰。
  - **改法**：`users` 表加 `welcome_kb_seeded TINYINT NOT NULL DEFAULT 0`（Flyway `V20260710_01__users_welcome_kb_seeded.sql`，同步改 `db/schema.sql`）；`User` 实体加 `Integer welcomeKbSeeded`；`seedWelcomeKnowledgeBase(long userId)` 签名改为 `seedWelcomeKnowledgeBase(User user)`——进方法先看 `user.welcomeKbSeeded==1` 则 return，种植成功后 `user.setWelcomeKbSeeded(1) + userMapper.updateById(user)` 打上永久标记。3 处调用点（普通注册第 180 行、微信注册第 119 行、`completeLogin` 老用户自检第 300 行）全部改成传 user 对象。
  - **迁移策略**（保守）：`UPDATE users SET welcome_kb_seeded = 1 WHERE deleted = 0` 把当前 28 个存量用户全部标记为已种。理由：无法从数据库区分"从未种过"与"种过后删掉"，全部视为已种可避免任何老用户被意外重种；新注册用户走新分支正常首次种植。
  - **验证**：Flyway 输出 `Successfully applied 1 migration ... now at version v20260710.01`；`SELECT welcome_kb_seeded, COUNT(*) FROM users` → `1: 28`。

- ✅ **"我的知识库"越权列出他人 KB**（2026-07-09 / 2026-07-10 补漏）：
  - **现象**：用户"我的知识库"页展示 16 个"AI知识库使用指南"卡片，实为 16 个不同用户各自被 `UserService.seedWelcomeKnowledgeBase()` 自动 seed 的 KB（owner_id=1、15-29），当前用户看到了别人的私有库。顶部"X 个知识库·Y 份文档·Z 已用"统计同样错。
  - **根因 1**：`AiWorkspaceService.listKnowledgeBases` 只在 `tab='mine'` 时按 owner 过滤，其它情况返回全租户 `deleted=0` 的所有 KB；`AiKnowledgeBase.visibility` 默认 `private` 但过滤器压根没读这个字段。
  - **根因 2**：`AiWorkspaceController.knowledgeBases` 的 `tab` 参数 `@RequestParam(defaultValue = "all")`，即使前端不传也会是 `"all"` 而非 null——因此 Service 端把 blank(tab) 视为 mine 是不够的，必须把 `"all"` 也一并收敛。
  - **根因 3（07-10 补）**：`AiWorkspaceService.knowledgeStats` 是同一 bug 的第二个入口，直接 `selectList(eq(deleted, 0))` 统计全租户 KB 数量/文档数/存储用量，同样没按 owner 过滤——页面顶部"共 16 个知识库"就是这里出来的。
  - **修复**：① 前端 `KnowledgeLibraries.vue::loadData` 显式传 `tab: 'mine'`；② 后端 `listKnowledgeBases` 增 `String effectiveTab = (blank(tab) || "all".equals(tab)) ? "mine" : tab;` 并在 mine 分支加 `q.eq(AiKnowledgeBase::getOwnerId, userId)` 强制 owner 过滤（作 A+B 双保险，防止未来任何调用点漏传参数再泄露）；③ `knowledgeStats` 加 `q.eq(AiKnowledgeBase::getOwnerId, userIdOrGuest())` 收敛到当前用户。
  - **不受影响**：`listAgents` / `listPlugins` 是广场语义，Agent/Plugin 无 `visibility=private`，且有 `mine`/`favorite` 显式 boolean 参数，本身设计成全租户可见，无需改。
  - 验证：`curl /api/v1/ai/knowledge-bases?[tab=mine|tab=all|无 tab]` 在游客态均返回 `{total:0, items:[]}`（游客 userId=0，无匹配 owner 的 KB）。

- ✅ **系统公告发布功能**（2026-07-05）：
  - 新建 `announcements` 表（多租户，未加入 `TENANT_IGNORE_TABLES`）：`id/tenant_id/title/summary/content(mediumtext,markdown)/level(info|warning|critical)/pinned/publisher_id/status(draft|published|archived)/publish_time/expire_time/(created|updated)_at/deleted`；Flyway 迁移 `V20260705_02__announcements.sql`（`_01` 已被 `ai_notes.sort_order` 占用，注意避让）。
  - 新包 `announcement/`：`domain/Announcement` 继承 `BaseEntity`；`mapper/AnnouncementMapper` 提供 `selectActive/selectPublicPage/selectTrashPage/restoreById/physicalDeleteById/batch*`；`service/AnnouncementServiceImpl` 用 `@Resource @Lazy self` 实现 `purgeBatchForAdmin`（per-item `REQUIRES_NEW` 事务），保存前走 `HtmlSanitizerService.sanitizePost` 剥离 XSS。新增 `ErrorCode.ANNOUNCEMENT_NOT_FOUND(10801)`。
  - 前台读接口 `AnnouncementController /announcements`：`GET active|/{|id}`；SaToken GET 默认放行。管理端 `AdminAnnouncementController /admin/announcements`：完整 CRUD + status/pin toggle + 回收站 + 4 个批量端点，权限 `tenant:announcement:manage`（已加入 `AdminStpInterface`，SUPER_ADMIN 继承）。新 DTO `BatchAnnouncementStatusRequest`（status ∈ draft/published/archived）。
  - **前端**：新组件 `components/AnnouncementBanner.vue`（在 `MainLayout` 顶栏所有非 AI 页面显示，支持翻页、按 level 变色、点关闭时 id 写 `localStorage.dismissed_announcement_ids`）；新页面 `pages/Announcements.vue`（列表）+ `pages/AnnouncementDetail.vue`（详情，用 `utils/markdown.ts#renderMarkdown` 渲染）；新管理页 `pages/admin/AdminAnnouncements.vue`（照 `AdminNotes.vue` 模板，含级别/置顶/发布时间/过期时间 NDatePicker + Markdown 预览切换）。
  - **审计 action**：`ANNOUNCEMENT_CREATE / UPDATE / STATUS / PIN / FORCE_DELETE / RESTORE / PURGE / BATCH_*`，`AdminDashboard.actionLabels` 已同步中文映射。
  - **路由**：`/announcements` `/announcements/:id` 加入游客白名单，`/admin/announcements` 加入 admin children + AdminLayout 菜单（`MegaphoneOutline` icon）。
  - **验证**：Flyway 成功 v20260705.02，表结构 + 索引齐全；未登录访 `POST /admin/announcements` → 401；expire_time 改为过去时立即从 `active` 消失并 detail 返回 `ANNOUNCEMENT_NOT_FOUND`。**注意**：docker exec 里用 `mysql -e "..."` 直接插中文会 double-encode 存成 latin1 乱码；应改用 `docker exec -i ... mysql --default-character-set=utf8mb4` + stdin 传 UTF-8 字节。

- ✅ **外部笔记同步 · 出处 + 友链**（2026-07-02 追加）：
  - `ai_notes` 表新增 `source_url / source_name / source_author` 三列（`V20260702_01__ai_notes_source.sql`）；空串表示本站原生笔记，非空表示同步进来的外部笔记。
  - 新 `NoteSyncService`（`ai/workspace/service/`）+ `POST /admin/notes/sync-external`：三种模式 `single` / `vitepress` / `cnblogs`；HTTP 客户端走 `NOTE_SYNC_PROXY_HOST/PORT`（默认 `host.docker.internal:7890`）+ `note-sync.allowed-hosts` 白名单严格限制外站域名，与 `SafeHttpClient` 的 SSRF 防线分离；jsoup 抽正文根（VitePress `.vp-doc` / cnblogs `#cnblogs_post_body`）+ 相对链接绝对化 + `FlexmarkHtmlConverter` 转 Markdown；确定性 ID `note_sync_ + sha256(url)[0..12]` 保证幂等（同 URL 再同步走 update）。
  - `docker-compose.yml` 给 app 服务加 `extra_hosts: host.docker.internal:host-gateway` 让容器可访问宿主 7890 代理；`deploy/.env` 加 `NOTE_SYNC_PROXY_HOST/PORT/ALLOWED_HOSTS`。
  - 前端：`NoteVO` 加 3 optional 字段；学习页卡片右上角 ↗ 角标 + meta 显示 "原文 · sourceName"；`NotePublicDetail.vue` 顶部出处 banner "本文由 X 授权同步 · 原文：[外链]"；`AdminNotes.vue` 加"同步外部笔记"按钮 + Modal 表单（source/rootUrl/recursive/sourceName/sourceAuthor/tags/ownerId）。
  - AI 问答天然可用：详情页 AI 面板吃 `note.content` 作 prompt context，同步进来的 markdown 自动进入对话上下文，无需改动。
  - 新增依赖：`com.vladsch.flexmark:flexmark-html2md-converter:0.64.8`（首次编译需网络下载，可加代理参数：`mvn -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890 compile`）。
  - **同步排障纪要 2026-07-02**：
    - 初次同步失败：容器打不到宿主 `mihomo` 代理，因为 `mihomo` 默认 `allow-lan: false` 只绑 `127.0.0.1:7890`；改 `/etc/mihomo/config.yaml` 为 `allow-lan: true` 后走 REST API 热重载（`PUT :9090/configs?force=true` + Bearer secret）即可，无需重启进程。
    - 二次失败：`Data too long for column 'id'` — `ai_notes.id` 是 `VARCHAR(32)`，我原来的 ID 前缀 `note_sync_` (10) + 12 bytes hex (24) = 34 字符超限。修为 `ns_` (3) + 14 bytes hex (28) = 31 字符。抓取和 HTML→Markdown 转换都正常。
    - 三次成功：VitePress 期末 recursive → 5 篇，Lab 单页 → 1 篇，cnblogs LFmin → 9 篇；但 cnblogs 9 篇标题全是"LFmin"（博主名），原因是 `pickTitle` 走 `.postTitle a`（那是列表页元素）在文章页匹配到了顶部导航的博主名。修为 cnblogs 专用分支：优先 `#cb_post_title_url / h1.postTitle`，回退用 `<title>` 按 " - " 分割取首段（cnblogs `<title>` 格式为 "文章标题 - 博主名 - 博客园"）。修完幂等重跑 cnblogs 全部 9 篇标题正确。
    - 关于 nginx：`POST /auth/login` 走 nginx 时 body 被吞返回 500 `HttpMessageNotReadableException: Required request body is missing`；直连 `http://127.0.0.1:8080` 正常。管理端浏览器登录不受影响（走 nginx 但走的是不同 location），仅 shell 侧调试要注意直连。


- ✅ **管理端批量操作 + 分页查询**（2026-07-02 追加）：
  - **6 个内容管理页统一改造为分页查询**：`AdminPosts / AdminSpaces / AdminResources / AdminNotes / AdminCheckin / AdminComments` 全部从"游标 + 加载更多"切到 Naive UI `NDataTable` 内置 `pagination`（默认 20/页，可切 10/50，`showQuickJumper`），后端 `list()` 端点参数由 `cursor/limit` 改为 `page/size`，返回 `R<PageResult<T>>`（`admin/dto/PageResult.java` + MP `IPage` 自动 COUNT+LIMIT）；6 个 Mapper 增补 `selectTrashPage(IPage, Wrapper)` 走 `PaginationInnerInterceptor`。
  - **24 个批量端点**（6 类 × 4 端点：`batch-status` / `batch` / `batch-restore` / `batch-purge`）：4 个 DTO（`BatchIdsRequest` / `BatchStatusRequest` / `BatchNoteIdsRequest` / `BatchNoteStatusRequest`）+ `@Size(max=100)` 兜底；6 个 Mapper 加 IN 查询原生 SQL（`batchLogicalDelete / batchRestore / batchSetStatus`）；6 个 Service 加 4 方法，`purgeBatchForAdmin` 用 `@Resource @Lazy self`（AOP 自调用陷阱兜底）循环调用现有单条 `purgeForAdmin` 保证 per-item 独立事务，返回 `{success, failed:[{id,reason}], counts}` 允许"部分成功"，Space 下有活跃 posts 之类的单条失败不拖累整批。
  - **前端 6 页统一新增**：`checkedRowKeys` + `row-key` + `{type:'selection'}` 列 + 顶部批量操作条（正常 tab：批量隐藏/恢复/删除；回收站 tab：批量恢复/彻底删除）；`PurgeConfirmModal` 复用于单条+批量，批量模式 summary 拼 "共 N 项：#id1、#id2…"，3s 倒计时防误点；跨页选择保留（切 tab 时清空）。
  - **审计**：`AuditLogService` 新增 24 个 action `XXX_BATCH_STATUS/DELETE/RESTORE/PURGE`，detail 含 `ids.size()` + 失败数 + 累计级联数；`AdminDashboard.actionLabels` 字典追加中文映射。
  - **注意**：`WS_TICKET_ENFORCED=true` 已追加到 `deploy/.env`（cutover 2026-07-01 已到），否则 `SecurityStartupValidator` 会阻止启动。
- ✅ **管理后台全面优化**（2026-07-02）：
  - Dashboard 换真数据：`DashboardVO` 扩 `weeklyTrend` / `spaceCategoryDist` / `recentAuditLogs`，前端 `AdminDashboard.vue` 6 张真实 count 卡 + 7 天 SVG 折线图 + 空间分类饼图 + 最近 5 条审计。
  - 新增 **4 个管理页 + 后端 controller**：`AdminResources` / `AdminNotes` / `AdminCheckin` / `AdminComments`（每类含 list / setStatus / delete / restore / purge 5 端点）。
  - **三段式回收站**：所有内容管理页（含 Posts / Spaces / 新增 4 类）加 `【正常】| 【回收站】` tab：`status=0/1` 控隐藏；`deleted=1` 进回收站；从回收站可 `恢复` 或 `彻底删除`；彻底删除走 3s 倒计时二次确认 (`PurgeConfirmModal.vue`)，级联清理 comments / reactions / post_ai_cards / qa_questions / space_members / OSS 文件。
  - **权限扩容**：`AdminStpInterface` 新增 `tenant:resource:manage` / `tenant:note:manage` / `tenant:checkin:manage` / `tenant:comment:manage` / `tenant:self:ai-config`，TENANT_ADMIN 现在也能改自己租户的 AI 配置；`TenantController` 4 个跨租户端点仍锁 `super:tenant:manage`，AI 配置端点内置 `assertSameTenantOrSuper` 校验。
  - **各 Mapper 加原生 SQL 回收站方法** (`selectTrash` / `restoreById` / `physicalDeleteById`) 绕过 MyBatis-Plus 逻辑删除拦截；级联 Mapper (`CommentMapper.physicalDeleteByPostId` / `ReactionMapper.physicalDeleteByTarget` 等) 用于 purge。
  - `checkin_challenges` 无 `deleted` 字段，语义借用 `status`：1 正常 / 2 隐藏 / 0 回收站；`ai_notes.status` 扩展 `hidden` 值（`draft/published/hidden`）。
  - 前端 `AdminLayout` 菜单加 4 项 + 按 role 过滤租户管理仅超管可见；`AdminAiConfig.vue` 对 TENANT_ADMIN 隐藏租户下拉、显示当前租户 tag。
- ✅ **管理后台租户名称乱码**（2026-06-24）
- ✅ 游客签名直链预览 Office 文件 500 崩溃（2026-06-24）
- ✅ **导航栏调整**：移除工具/软件，恢复打卡（2026-06-28）
- ✅ **AI 助手页面重构**：用 `前端/pages/knowledge/` 静态 HTML 替换旧的 AiAssistant.vue，拆分为 AiChat/KnowledgeLibraries/KnowledgeNotes 三个页面（2026-06-28）
- ✅ **笔记系统**：ai_notes 表 + CRUD API + Markdown 编辑器（textarea+marked）+ 工具栏 toggle + 图片上传 + 链接对话框（2026-06-28 ~ 2026-06-29）
- ✅ **AI 对话框固定 + 模型选择 + 限流**：右侧面板 fixed 定位；AiChat 支持切换 4 个模型（pill 按钮）；分级限流（Pro 10/h, 普通 20/h）+ 前端展示剩余次数（2026-06-30）
- ✅ **打卡页假数据替换**：移除全部硬编码挑战/动态/排行榜/日历假数据，接入真实 API（2026-06-29）
- ✅ **学习页**：精选教程网格 + 公开笔记列表 + 教程阅读器（章节目录/正文/右侧AI）+ 教程抓取系统（12 套 646 章菜鸟教程同步入库）（2026-06-29 ~ 2026-06-30）
- ✅ **CORS PATCH 修复**：SecurityProperties.java allowedMethods 缺失 PATCH 导致 403（2026-06-29）
- ✅ **公开笔记 API**：GET /notes/public 列表 + 详情（2026-06-29）
- ✅ `application.yml` 基础层 datasource/redis 去硬编码 IP/口令 → `${VAR:localhost}` 占位。
- ✅ `docker-compose.yml` kkfileview 默认值修正（线上 `.env` 已覆盖为 `http://localhost:8012/onlinePreview`）。
- ✅ 过时 MinIO 注释清理（`StorageService` javadoc 等处，源码确无 MinIO 实现）。
- ✅ `.gitignore` 拦截 1panel/压缩包误提交；删除根目录 `package-lock.json` 空壳。
- ✅ 移除未使用的 `socket.io-client`（`npm install` 同步 lockfile 已验证）。
- ✅ `OpenAiCompatService` / `MeiliSearchClient` 中 `ObjectMapper` 改为 static final 单例。
- ✅ `agent.md` 顶部加「已被 agents.md 取代」说明。
- ✅ **Flyway 数据库自动迁移**：添加 `flyway-mysql` 依赖，配置 `spring.flyway.{locations=classpath:db/migration, baseline-on-migrate=true, baseline-version=20260624_01}`。已有 15 个历史 V* 迁移复制到 classpath。启动时自动执行新迁移，存量生产库平滑过渡（baseline 跳过历史）。
- ✅ **AI 死配置清理**（2026-07-11）：顶层 `ai.provider/base-url/api-key/model` 与 ENV `AI_PROVIDER/AI_API_KEY/AI_BASE_URL/AI_MODEL`、`JWT_SECRET` 从 application.yml / application-prod.yml / docker-compose.yml / deploy/.env / .env.example 全部删除（均为代码零引用的死配置）。`ai.providers.{deepseek,mimo}` 保留。
- ✅ **DeepSeek 旧别名 `deepseek-chat` 全部替换为 `deepseek-v4-flash`**（`OpenAiCompatService` 默认值 + 测试 + `deploy/.env`），`deepseek-chat`/`deepseek-reasoner` 将于 2026-07-24 被 DeepSeek 停用。
- ✅ **AI 工作台 JSON → MySQL 持久化**：新建 10 张表（`ai_agents/plugins/knowledge_bases/documents/qa_pairs/ingest_tasks/conversations/messages/favorites/installed_plugins`），各含 Entity + Mapper（MyBatis-Plus）。`AiWorkspaceService` 完整重写（~500 行，注入 11 个 Mapper 替换 12 个 ConcurrentHashMap + 1 个 JSON 文件）。Controller 接口完全不变，前端零改动。`backend/data/ai-workspace.json` 文件依赖已移除。
- ✅ **前端 boot 重定向收敛**：`main.ts` 删除与 router `redirect` 重复的三条 legacy 路径映射，仅保留首页已登录跳转（性能优化）。
- ✅ **公开笔记详情页 AI 识别不到当前笔记**（2026-07-05）：`NotePublicDetail.vue::handleAiSend` 拼了 `context` 变量但从未传给 `sendMessage` — dead variable。`SendMessageRequest` 类型没有 context 字段、后端 `AiWorkspaceService.sendMessage` 也不吃 body 里的 context，只从 `buildConvContext(agentId, plugins, kbs)` 拿。修：`SendMessageRequest` 加可选 `attachedContext`，后端从 body 取 `attachedContext` 拼到内部 `context` 前，`NotePublicDetail.vue` 调用 `sendMessage` 时把 note 正文塞进 `attachedContext`。**API 陷阱备忘**：conversations API 的 sendMessage 每次**只把当前一句** user message 发给 AI（不加载历史 messages），页面级上下文（笔记正文、教程章节等）必须**每次调用都显式重传** `attachedContext`；如果对话历史 AI 记忆也很重要，考虑改用 `aiRagChat` 或后续给 sendMessage 加历史消息注入。
- ✅ **笔记页 3 个 UI Bug 修复**（2026-07-01，`KnowledgeNotes.vue`/`KnowledgeSidebar.vue`，已构建 + 重启 nginx 上线）：① 左侧导航栏收起按钮难点击——`.sidebar` 的 `backdrop-filter` 创建独立层叠上下文困住按钮 z-index，被相邻笔记列表面板覆盖，给 `.sidebar` 加 `z-index:20` 抬升；② 右侧 AI 面板收起按钮不显示——`.notes-ai-panel` 的 `backdrop-filter` 为 fixed 后代创建包含块、`overflow:hidden` 裁掉按钮，将 `.panel-toggle-btn` 移出面板作为 `.notes-layout` 直接子节点；③ **预览目录"乱码"——真正根因是 CSS flex 挤压**：`.editor-toc-nav` 是 `flex-direction:column` + `overflow-y:auto`，目录项 `.editor-toc-item` 默认 `flex-shrink:1`，当标题很多（如"java课后习题合集"75 个标题）时被竖向压扁成一团、文字重叠，误看成乱码——修复是给 `.editor-toc-item`/`.editor-toc-back-top` 加 `flex-shrink:0`（少标题时不触发，故小样本测不出）。同时顺带把目录提取从正则重构为 `utils/markdown.ts#renderMarkdown`（DOMParser 从真实 `<h1-3>` 统一产出 HTML+目录，消除代码块内 `#` 误判与锚点错位的**潜在** bug）。**注意** `NotePublicDetail.vue` 仍是旧正则版且**未加 flex-shrink:0**（同款目录 bug 未修，按用户要求本次只修编辑页）。
- ↩️ Dockerfile 21→17 已回退（线上容器运行 JDK 21，编译目标 17 是合理的，无需改动）。
- ↩️ AI 默认模型名 `deepseek-v4-flash`：联网核实后确认为 DeepSeek 官方有效模型名（误判已撤销）。
