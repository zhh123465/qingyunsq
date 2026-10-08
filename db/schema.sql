-- ============================================================
-- CampusForum Database Schema
-- 版本: v1.0
-- 字符集: utf8mb4 + utf8mb4_0900_ai_ci
-- ============================================================

CREATE DATABASE IF NOT EXISTS campus_forum
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;

-- 关键：docker-compose 的 MYSQL_DATABASE 会在 initdb 脚本之前用「服务器默认字符集」预建库，
-- 此时上面的 CREATE DATABASE IF NOT EXISTS 成为 no-op，库默认字符集可能不是 utf8mb4，
-- 导致后续未显式声明 charset 的建表全部继承错误字符集、中文落库即乱码。
-- 这里用 ALTER DATABASE 强制把库默认字符集纠正为 utf8mb4，再创建各表。
ALTER DATABASE campus_forum
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

USE campus_forum;

-- ============================================================
-- 1. tenants 租户/学校
-- ============================================================
CREATE TABLE tenants (
  id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  code         VARCHAR(32)  NOT NULL COMMENT '租户编码（用于子域名）',
  name         VARCHAR(128) NOT NULL COMMENT '学校全称',
  logo_url     VARCHAR(255) DEFAULT NULL,
  domain       VARCHAR(128) DEFAULT NULL,
  status       TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  ai_config    JSON DEFAULT NULL COMMENT 'AI 配置',
  announcement VARCHAR(500) DEFAULT NULL COMMENT '租户公告',
  created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户/学校';

-- ============================================================
-- 2. users 用户
-- ============================================================
CREATE TABLE users (
  id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id       BIGINT UNSIGNED NOT NULL,
  student_no      VARCHAR(32)  DEFAULT NULL COMMENT '学号',
  email           VARCHAR(128) NOT NULL,
  password_hash   VARCHAR(128) NOT NULL,
  nickname        VARCHAR(64)  NOT NULL,
  avatar_url      VARCHAR(255) DEFAULT NULL,
  wechat_openid   VARCHAR(64)  DEFAULT NULL COMMENT '微信小程序 openid',
  wechat_unionid  VARCHAR(64)  DEFAULT NULL COMMENT '微信开放平台 unionid',
  qq_openid       VARCHAR(64)  DEFAULT NULL COMMENT 'QQ openid',
  github_id       VARCHAR(64)  DEFAULT NULL COMMENT 'GitHub 用户 id',
  profile_cover_url VARCHAR(255) DEFAULT NULL COMMENT '个人主页封面图',
  bio             VARCHAR(255) DEFAULT NULL,
  college         VARCHAR(64)  DEFAULT NULL COMMENT '学院',
  major           VARCHAR(64)  DEFAULT NULL COMMENT '专业',
  grade           VARCHAR(8)   DEFAULT NULL COMMENT '年级',
  role            VARCHAR(32)  NOT NULL DEFAULT 'USER' COMMENT 'USER/TENANT_ADMIN/SUPER_ADMIN',
  status          TINYINT NOT NULL DEFAULT 1 COMMENT '1正常 0封禁',
  last_login_at   DATETIME DEFAULT NULL,
  reset_token     VARCHAR(64)  DEFAULT NULL COMMENT '密码重置令牌 SHA-256 哈希（hex）',
  reset_token_expires DATETIME DEFAULT NULL COMMENT '密码重置令牌过期时间',
  mute_settings   JSON DEFAULT NULL COMMENT '消息免打扰设置',
  tag_subscriptions JSON DEFAULT NULL COMMENT '问答标签订阅',
  welcome_kb_seeded TINYINT NOT NULL DEFAULT 0 COMMENT '欢迎知识库是否已种植过（一次性，用户删掉后不再重种）',
  created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted         TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_tenant_email (tenant_id, email),
  UNIQUE KEY uk_tenant_student (tenant_id, student_no),
  UNIQUE KEY uk_tenant_wechat_openid (tenant_id, wechat_openid),
  UNIQUE KEY uk_tenant_qq_openid (tenant_id, qq_openid),
  UNIQUE KEY uk_tenant_github_id (tenant_id, github_id),
  KEY idx_tenant (tenant_id)
) ENGINE=InnoDB COMMENT='用户';

-- ============================================================
-- 3. spaces 学习空间
-- ============================================================
CREATE TABLE spaces (
  id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id     BIGINT UNSIGNED NOT NULL,
  owner_id      BIGINT UNSIGNED NOT NULL,
  name          VARCHAR(64)  NOT NULL,
  description   VARCHAR(255) DEFAULT NULL,
  category      VARCHAR(16)  NOT NULL COMMENT 'MAJOR/CLASS/CLUB/INTEREST',
  visibility    VARCHAR(16)  NOT NULL DEFAULT 'PUBLIC' COMMENT 'PUBLIC/REVIEW/INVITE',
  cover_url     VARCHAR(255) DEFAULT NULL,
  sensitive_words TEXT DEFAULT NULL COMMENT '空间自定义敏感词',
  post_notice   VARCHAR(500) DEFAULT NULL COMMENT '发帖须知',
  member_count  INT NOT NULL DEFAULT 0,
  post_count    INT NOT NULL DEFAULT 0,
  status        TINYINT NOT NULL DEFAULT 1,
  created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted       TINYINT NOT NULL DEFAULT 0,
  KEY idx_tenant_category (tenant_id, category),
  KEY idx_owner (owner_id)
) ENGINE=InnoDB COMMENT='学习空间';

-- ============================================================
-- 4. space_members 空间成员
-- ============================================================
CREATE TABLE space_members (
  id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id   BIGINT UNSIGNED NOT NULL,
  space_id    BIGINT UNSIGNED NOT NULL,
  user_id     BIGINT UNSIGNED NOT NULL,
  role        VARCHAR(16) NOT NULL DEFAULT 'MEMBER' COMMENT 'OWNER/ADMIN/MEMBER',
  status      TINYINT NOT NULL DEFAULT 0 COMMENT '0待审核 1已加入 2已退出 3已拒绝',
  joined_at   DATETIME DEFAULT NULL,
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_space_user (space_id, user_id),
  KEY idx_user (user_id)
) ENGINE=InnoDB COMMENT='空间成员';

-- ============================================================
-- 5. posts 帖子（统一表）
-- ============================================================
CREATE TABLE posts (
  id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id     BIGINT UNSIGNED NOT NULL,
  author_id     BIGINT UNSIGNED NOT NULL,
  scope         VARCHAR(8)  NOT NULL COMMENT 'SQUARE/SPACE',
  space_id      BIGINT UNSIGNED DEFAULT NULL,
  type          VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/QA/CHECKIN/RESOURCE',
  title         VARCHAR(255) DEFAULT NULL,
  content       MEDIUMTEXT NOT NULL,
  attachments   JSON DEFAULT NULL,
  topics        JSON DEFAULT NULL COMMENT '话题',
  tags          JSON DEFAULT NULL,
  ai_summary    TEXT DEFAULT NULL COMMENT 'AI 生成摘要',
  ai_risk_level TINYINT DEFAULT 0 COMMENT '0正常 1中风险 2高风险',
  view_count    INT NOT NULL DEFAULT 0,
  like_count    INT NOT NULL DEFAULT 0,
  comment_count INT NOT NULL DEFAULT 0,
  is_pinned     TINYINT NOT NULL DEFAULT 0,
  is_essence    TINYINT NOT NULL DEFAULT 0,
  status        TINYINT NOT NULL DEFAULT 1 COMMENT '0待审 1正常 2隐藏',
  pinned_at     DATETIME DEFAULT NULL COMMENT '置顶时间',
  created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted       TINYINT NOT NULL DEFAULT 0,
  KEY idx_tenant_scope_time (tenant_id, scope, created_at),
  KEY idx_space_time (space_id, created_at),
  KEY idx_author (author_id),
  FULLTEXT KEY ft_title_content (title, content) /*!50700 WITH PARSER ngram */
) ENGINE=InnoDB COMMENT='帖子';

-- ============================================================
-- 6. comments 评论
-- ============================================================
CREATE TABLE comments (
  id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id   BIGINT UNSIGNED NOT NULL,
  post_id     BIGINT UNSIGNED NOT NULL,
  parent_id   BIGINT UNSIGNED DEFAULT NULL,
  reply_to_id BIGINT UNSIGNED DEFAULT NULL,
  author_id   BIGINT UNSIGNED NOT NULL,
  content     TEXT NOT NULL,
  like_count  INT NOT NULL DEFAULT 0,
  status      TINYINT NOT NULL DEFAULT 1,
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME DEFAULT NULL COMMENT '最后编辑时间',
  deleted     TINYINT NOT NULL DEFAULT 0,
  KEY idx_post (post_id, created_at),
  KEY idx_author (author_id)
) ENGINE=InnoDB COMMENT='评论';

-- ============================================================
-- 7. reactions 点赞/收藏
-- ============================================================
CREATE TABLE reactions (
  id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id   BIGINT UNSIGNED NOT NULL,
  user_id     BIGINT UNSIGNED NOT NULL,
  target_type VARCHAR(16) NOT NULL COMMENT 'POST/COMMENT/RESOURCE',
  target_id   BIGINT UNSIGNED NOT NULL,
  type        VARCHAR(16) NOT NULL COMMENT 'LIKE/COLLECT',
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_user_target (user_id, target_type, target_id, type),
  KEY idx_target (target_type, target_id)
) ENGINE=InnoDB COMMENT='点赞收藏';

-- ============================================================
-- 8. qa_questions 问答扩展
-- ============================================================
CREATE TABLE qa_questions (
  id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id       BIGINT UNSIGNED NOT NULL,
  post_id         BIGINT UNSIGNED NOT NULL,
  is_solved       TINYINT NOT NULL DEFAULT 0,
  accepted_comment_id BIGINT UNSIGNED DEFAULT NULL,
  solved_at       DATETIME DEFAULT NULL,
  UNIQUE KEY uk_post (post_id)
) ENGINE=InnoDB COMMENT='问答扩展';

-- ============================================================
-- 9. checkin_challenges 打卡挑战
-- ============================================================
CREATE TABLE checkin_challenges (
  id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id     BIGINT UNSIGNED NOT NULL,
  space_id      BIGINT UNSIGNED DEFAULT NULL,
  creator_id    BIGINT UNSIGNED NOT NULL,
  name          VARCHAR(64) NOT NULL,
  description   VARCHAR(500) DEFAULT NULL,
  start_date    DATE NOT NULL,
  end_date      DATE NOT NULL,
  rule          JSON DEFAULT NULL,
  member_count  INT NOT NULL DEFAULT 0,
  status        TINYINT NOT NULL DEFAULT 1,
  created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB COMMENT='打卡挑战';

-- ============================================================
-- 10. checkin_records 打卡记录
-- ============================================================
CREATE TABLE checkin_records (
  id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id     BIGINT UNSIGNED NOT NULL,
  challenge_id  BIGINT UNSIGNED NOT NULL,
  user_id       BIGINT UNSIGNED NOT NULL,
  checkin_date  DATE NOT NULL,
  content       TEXT DEFAULT NULL,
  image_urls    JSON DEFAULT NULL,
  ai_check      TINYINT DEFAULT 0 COMMENT 'AI 内容合规校验',
  created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_user_date (challenge_id, user_id, checkin_date),
  KEY idx_user (user_id, checkin_date)
) ENGINE=InnoDB COMMENT='打卡记录';

-- ============================================================
-- 11. resources 资源
-- ============================================================
CREATE TABLE resources (
  id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id     BIGINT UNSIGNED NOT NULL,
  uploader_id   BIGINT UNSIGNED NOT NULL,
  space_id      BIGINT UNSIGNED DEFAULT NULL,
  file_name     VARCHAR(255) NOT NULL,
  file_size     BIGINT UNSIGNED NOT NULL,
  file_type     VARCHAR(32)  NOT NULL,
  file_md5      VARCHAR(64)  DEFAULT NULL COMMENT '@Deprecated - 保留至历史数据 100% 迁移到 file_sha256（spec T8.10）',
  file_sha256   VARCHAR(64)  DEFAULT NULL COMMENT 'SHA-256 hex 指纹',
  storage_key   VARCHAR(255) NOT NULL,
  visibility    VARCHAR(16)  NOT NULL DEFAULT 'PUBLIC' COMMENT 'PUBLIC/SPACE/PRIVATE',
  college       VARCHAR(64)  DEFAULT NULL,
  major         VARCHAR(64)  DEFAULT NULL,
  course        VARCHAR(128) DEFAULT NULL,
  semester      VARCHAR(16)  DEFAULT NULL,
  tags          JSON DEFAULT NULL,
  download_count INT NOT NULL DEFAULT 0,
  collect_count  INT NOT NULL DEFAULT 0,
  version       VARCHAR(32)  DEFAULT NULL,
  description   TEXT DEFAULT NULL,
  status        TINYINT NOT NULL DEFAULT 2 COMMENT '0=隐藏 1=已发布 2=待审核 3=已驳回',
  review_reason VARCHAR(255) DEFAULT NULL COMMENT '驳回原因（status=3 时有值）',
  reviewed_by   BIGINT UNSIGNED DEFAULT NULL COMMENT '审核人用户 ID',
  reviewed_at   DATETIME DEFAULT NULL COMMENT '审核时间',
  created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted       TINYINT NOT NULL DEFAULT 0,
  KEY idx_tenant (tenant_id),
  KEY idx_uploader (uploader_id),
  KEY idx_space (space_id),
  KEY idx_md5 (file_md5),
  KEY idx_resources_file_sha256 (file_sha256),
  KEY idx_resources_tenant_status (tenant_id, status)
) ENGINE=InnoDB COMMENT='资源';

-- ============================================================
-- 12. notifications 通知
-- ============================================================
CREATE TABLE notifications (
  id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id    BIGINT UNSIGNED NOT NULL,
  receiver_id  BIGINT UNSIGNED NOT NULL,
  sender_id    BIGINT UNSIGNED DEFAULT NULL,
  type         VARCHAR(32) NOT NULL COMMENT 'COMMENT/LIKE/REPLY/MENTION/ACCEPT/JOIN/RESOURCE_REVIEW/SYSTEM',
  title        VARCHAR(128) NOT NULL,
  content      TEXT DEFAULT NULL,
  redirect_url VARCHAR(255) DEFAULT NULL,
  is_read      TINYINT NOT NULL DEFAULT 0,
  created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_receiver_read_time (receiver_id, is_read, created_at)
) ENGINE=InnoDB COMMENT='通知';

-- ============================================================
-- 12.1 announcements 系统公告（管理员发布，前台横幅+独立公告页展示）
-- ============================================================
CREATE TABLE announcements (
  id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id     BIGINT UNSIGNED NOT NULL,
  title         VARCHAR(200)    NOT NULL,
  summary       VARCHAR(255)    DEFAULT NULL COMMENT '横幅短摘要，NULL 时前端截 content 前 80 字',
  content       MEDIUMTEXT      NOT NULL COMMENT 'Markdown 源码',
  level         VARCHAR(16)     NOT NULL DEFAULT 'info' COMMENT 'info/warning/critical',
  pinned        TINYINT         NOT NULL DEFAULT 0,
  publisher_id  BIGINT UNSIGNED NOT NULL,
  status        VARCHAR(16)     NOT NULL DEFAULT 'draft' COMMENT 'draft/published/archived',
  publish_time  DATETIME        DEFAULT NULL COMMENT 'NULL=立即生效；否则到点才对前台可见',
  expire_time   DATETIME        DEFAULT NULL COMMENT 'NULL=永不过期',
  created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted       TINYINT         NOT NULL DEFAULT 0,
  KEY idx_active (tenant_id, status, deleted, pinned, publish_time),
  KEY idx_expire (expire_time)
) ENGINE=InnoDB COMMENT='系统公告';

-- ============================================================
-- 13. messages 私信
-- ============================================================
CREATE TABLE messages (
  id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id   BIGINT UNSIGNED NOT NULL,
  sender_id   BIGINT UNSIGNED NOT NULL,
  receiver_id BIGINT UNSIGNED NOT NULL,
  content     TEXT DEFAULT NULL,
  image_url   VARCHAR(255) DEFAULT NULL,
  ai_risk_level TINYINT NOT NULL DEFAULT 0 COMMENT '0=安全 1=疑似 2=违规（来自 SensitiveWordService.getRiskLevel，spec T8.10）',
  is_read     TINYINT NOT NULL DEFAULT 0,
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_conversation (sender_id, receiver_id, created_at),
  KEY idx_receiver_read (receiver_id, is_read, created_at)
) ENGINE=InnoDB COMMENT='私信';

-- ============================================================
-- 14. audit_logs 审计日志
-- ============================================================
CREATE TABLE audit_logs (
  id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id    BIGINT UNSIGNED NOT NULL,
  operator_id  BIGINT UNSIGNED DEFAULT NULL,
  action       VARCHAR(64)  NOT NULL COMMENT '操作类型',
  target_type  VARCHAR(32)  DEFAULT NULL,
  target_id    BIGINT UNSIGNED DEFAULT NULL,
  detail       JSON DEFAULT NULL COMMENT '操作详情',
  ip_address   VARCHAR(64)  DEFAULT NULL,
  user_agent   VARCHAR(255) DEFAULT NULL COMMENT '客户端 UA（含异步线程上下文，T9.2）',
  created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_tenant_time (tenant_id, created_at),
  KEY idx_operator (operator_id),
  KEY idx_audit_log_action_created (action, created_at)
) ENGINE=InnoDB COMMENT='审计日志';

-- ============================================================
-- 15. reports 举报
-- ============================================================
CREATE TABLE reports (
  id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id    BIGINT UNSIGNED NOT NULL,
  reporter_id  BIGINT UNSIGNED NOT NULL,
  target_type  VARCHAR(16) NOT NULL COMMENT 'POST/COMMENT/RESOURCE/USER',
  target_id    BIGINT UNSIGNED NOT NULL,
  reason       VARCHAR(32)  NOT NULL,
  description  TEXT DEFAULT NULL,
  status       TINYINT NOT NULL DEFAULT 0 COMMENT '0待处理 1已处理 2已驳回',
  handler_id   BIGINT UNSIGNED DEFAULT NULL,
  handle_note  TEXT DEFAULT NULL,
  created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  handled_at   DATETIME DEFAULT NULL,
  KEY idx_tenant_status (tenant_id, status)
) ENGINE=InnoDB COMMENT='举报';

-- ============================================================
-- 16. sensitive_words 敏感词
-- ============================================================
CREATE TABLE sensitive_words (
  id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id   BIGINT UNSIGNED NOT NULL,
  word        VARCHAR(64) NOT NULL,
  level       TINYINT NOT NULL DEFAULT 1 COMMENT '1低 2中 3高',
  is_regex    TINYINT NOT NULL DEFAULT 0 COMMENT '0=普通词 1=正则表达式（漏洞 27 / T8.5）',
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_tenant_word (tenant_id, word)
) ENGINE=InnoDB COMMENT='敏感词';

-- ============================================================
-- 17. achievements 成就
-- ============================================================
CREATE TABLE achievements (
  id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  code        VARCHAR(32)  NOT NULL COMMENT '成就编码',
  name        VARCHAR(64)  NOT NULL,
  description VARCHAR(255) DEFAULT NULL,
  icon_url    VARCHAR(255) DEFAULT NULL,
  rule        JSON DEFAULT NULL COMMENT '触发规则',
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_code (code)
) ENGINE=InnoDB COMMENT='成就定义';

CREATE TABLE user_achievements (
  id             BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id      BIGINT UNSIGNED NOT NULL,
  user_id        BIGINT UNSIGNED NOT NULL,
  achievement_id BIGINT UNSIGNED NOT NULL,
  awarded_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_user_achieve (user_id, achievement_id)
) ENGINE=InnoDB COMMENT='用户成就';


-- ============================================================
-- 19. follows 用户关注
-- ============================================================
CREATE TABLE follows (
  id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id   BIGINT UNSIGNED NOT NULL,
  follower_id BIGINT UNSIGNED NOT NULL COMMENT '关注者',
  followee_id BIGINT UNSIGNED NOT NULL COMMENT '被关注者',
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_follow (follower_id, followee_id),
  KEY idx_followee (followee_id)
) ENGINE=InnoDB COMMENT='用户关注';

-- ============================================================
-- 20. post_ai_cards 帖子 AI 智能卡片缓存
-- （合并迁移 V20260523_01 + V20260524_01/_02 的最终结构）
-- ============================================================
CREATE TABLE post_ai_cards (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL DEFAULT 1,
  post_id BIGINT NOT NULL,
  tldr VARCHAR(255) DEFAULT NULL COMMENT '一句话核心结论',
  audience VARCHAR(120) DEFAULT NULL COMMENT '适合谁读',
  value_type VARCHAR(20) DEFAULT NULL COMMENT '提问/经验/资源/吐槽/招募/讨论',
  read_minutes INT DEFAULT NULL COMMENT '估计阅读时长（分钟）',
  comment_consensus VARCHAR(500) DEFAULT NULL COMMENT '高赞共识答案',
  comment_disputes VARCHAR(500) DEFAULT NULL COMMENT '主要争议点',
  hot_comment_id BIGINT DEFAULT NULL COMMENT '点赞最多的评论 ID（用于跳转锚点）',
  hot_comment_excerpt VARCHAR(200) DEFAULT NULL COMMENT '热门评论摘录（截断到 80 字）',
  highlights VARCHAR(400) DEFAULT NULL COMMENT 'AI 提取重点，JSON 数组',
  post_version BIGINT NOT NULL DEFAULT 0 COMMENT '生成时帖子 updated_at 时间戳（毫秒）',
  comment_count_snapshot INT NOT NULL DEFAULT 0 COMMENT '生成时的评论数',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_post_id (post_id),
  KEY idx_tenant_post (tenant_id, post_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='帖子 AI 智能卡片缓存';

-- ============================================================
-- 18-27. AI 工作台表（2026-06-24 从 JSON 文件迁移到 MySQL）
-- ============================================================

-- ai_agents / ai_plugins / ai_user_installed_plugins 已于 2026-07-12 裁撤
-- （V20260712_01__drop_agents_plugins.sql，前端从未接入该产品线）

CREATE TABLE IF NOT EXISTS ai_knowledge_bases (
    id             VARCHAR(32)  NOT NULL PRIMARY KEY,
    tenant_id      BIGINT UNSIGNED NOT NULL DEFAULT 1,
    name           VARCHAR(128) NOT NULL DEFAULT 'Untitled Knowledge Base',
    description    TEXT,
    category       VARCHAR(64)  DEFAULT 'General',
    type           VARCHAR(64)  DEFAULT 'General',
    visibility     VARCHAR(32)  DEFAULT 'private',
    document_count BIGINT       NOT NULL DEFAULT 0,
    vector_count   BIGINT       NOT NULL DEFAULT 0,
    storage_bytes  BIGINT       NOT NULL DEFAULT 0,
    owner_id       BIGINT       NOT NULL DEFAULT 0,
    deleted        TINYINT      NOT NULL DEFAULT 0,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_ai_kb_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 知识库';

CREATE TABLE IF NOT EXISTS ai_kb_documents (
    id               VARCHAR(32)  NOT NULL PRIMARY KEY,
    knowledge_base_id VARCHAR(32) NOT NULL,
    file_name        VARCHAR(255),
    file_size        BIGINT       NOT NULL DEFAULT 0,
    tags             JSON DEFAULT NULL,
    parse_mode       VARCHAR(32)  DEFAULT 'auto',
    status           VARCHAR(32)  DEFAULT 'ready',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ai_docs_kb (knowledge_base_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 知识库文档';

CREATE TABLE IF NOT EXISTS ai_kb_qa_pairs (
    id               VARCHAR(32) NOT NULL PRIMARY KEY,
    knowledge_base_id VARCHAR(32) NOT NULL,
    question         TEXT,
    answer           TEXT,
    tags             JSON DEFAULT NULL,
    created_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ai_qa_kb (knowledge_base_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 知识库问答对';

CREATE TABLE IF NOT EXISTS ai_ingest_tasks (
    task_id          VARCHAR(32) NOT NULL PRIMARY KEY,
    knowledge_base_id VARCHAR(32) NOT NULL,
    status           VARCHAR(32) DEFAULT 'pending',
    progress         INT DEFAULT 0,
    message          VARCHAR(512)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 文档解析任务';

CREATE TABLE IF NOT EXISTS ai_kb_chunks (
    id                BIGINT       NOT NULL PRIMARY KEY AUTO_INCREMENT,
    knowledge_base_id VARCHAR(32)  NOT NULL,
    document_id       VARCHAR(32)  NOT NULL,
    chunk_index       INT          NOT NULL DEFAULT 0,
    content           TEXT         NOT NULL,
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ai_chunks_kb (knowledge_base_id),
    INDEX idx_ai_chunks_doc (document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 知识库文档切块（RAG 检索单元，2026-07-12）';

CREATE TABLE IF NOT EXISTS ai_conversations (
    id        VARCHAR(32)  NOT NULL PRIMARY KEY,
    tenant_id BIGINT UNSIGNED NOT NULL DEFAULT 1,
    title     VARCHAR(255) NOT NULL DEFAULT 'New conversation',
    model     VARCHAR(128) NOT NULL DEFAULT 'deepseek-v4-flash',
    knowledge_base_ids JSON DEFAULT NULL,
    owner_id  BIGINT       NOT NULL,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_ai_conv_owner (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 对话会话';

CREATE TABLE IF NOT EXISTS ai_messages (
    id              VARCHAR(32)  NOT NULL PRIMARY KEY,
    conversation_id VARCHAR(32)  NOT NULL,
    role            VARCHAR(16)  NOT NULL,
    content         MEDIUMTEXT,
    model           VARCHAR(128) DEFAULT '',
    knowledge_base_ids JSON DEFAULT NULL,
    attachments     JSON DEFAULT NULL,
    feedback        JSON DEFAULT NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ai_msg_conv (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 对话消息';

CREATE TABLE IF NOT EXISTS ai_user_favorites (
    user_id       BIGINT       NOT NULL,
    favorite_type VARCHAR(32)  NOT NULL,
    target_id     VARCHAR(32)  NOT NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, favorite_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 收藏关系';

CREATE TABLE IF NOT EXISTS ai_notes (
    id                 VARCHAR(32)  NOT NULL PRIMARY KEY,
    tenant_id          BIGINT       NOT NULL DEFAULT 0,
    title              VARCHAR(255) NOT NULL DEFAULT '',
    content            MEDIUMTEXT,
    content_oss_key    VARCHAR(255) DEFAULT NULL COMMENT '正文 OSS 对象 key（V20260630_02 起正文存 OSS，此列非空时 content 为 NULL）',
    content_type       VARCHAR(32)  DEFAULT 'markdown',
    tags               JSON,
    status             VARCHAR(32)  DEFAULT 'draft' COMMENT 'draft=草稿 pending=待审核 published=已发布 rejected=已驳回 hidden=管理员隐藏',
    review_reason      VARCHAR(255) DEFAULT NULL COMMENT '驳回原因（status=rejected 时有值）',
    reviewed_by        BIGINT UNSIGNED DEFAULT NULL COMMENT '审核人用户 ID',
    reviewed_at        DATETIME     DEFAULT NULL COMMENT '审核时间',
    view_count         BIGINT       DEFAULT 0,
    owner_id           BIGINT       NOT NULL DEFAULT 0,
    knowledge_base_id  VARCHAR(32)  DEFAULT NULL,
    source_url         VARCHAR(512) NOT NULL DEFAULT '' COMMENT '外部原文 URL（非空表示同步的友链笔记）',
    source_name        VARCHAR(128) NOT NULL DEFAULT '' COMMENT '原站显示名',
    source_author      VARCHAR(128) NOT NULL DEFAULT '' COMMENT '原作者显示名',
    sort_order         INT          NOT NULL DEFAULT 0 COMMENT '展示权重（数值大靠前，管理员编辑排序时写入）',
    deleted            TINYINT      DEFAULT 0,
    created_at         DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_ai_notes_owner (owner_id),
    INDEX idx_ai_notes_status (status),
    INDEX idx_ai_notes_kb (knowledge_base_id),
    INDEX idx_ai_notes_source_url (source_url)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 笔记';

CREATE TABLE IF NOT EXISTS learning_tutorials (
    id            VARCHAR(32)  NOT NULL PRIMARY KEY,
    title         VARCHAR(255) NOT NULL DEFAULT '',
    slug          VARCHAR(64)  NOT NULL,
    description   TEXT,
    source        VARCHAR(64)  DEFAULT '',
    source_url    VARCHAR(512) DEFAULT '',
    category      VARCHAR(32)  DEFAULT '',
    icon          VARCHAR(32)  DEFAULT '',
    lesson_count  INT          DEFAULT 0,
    sort_order    INT          DEFAULT 0,
    created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_learning_tut_slug (slug)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学习教程系列（抓取自公开免费来源）';

CREATE TABLE IF NOT EXISTS learning_lessons (
    id           VARCHAR(32)  NOT NULL PRIMARY KEY,
    tutorial_id  VARCHAR(32)  NOT NULL,
    title        VARCHAR(255) NOT NULL DEFAULT '',
    content      MEDIUMTEXT,
    source_url   VARCHAR(512) DEFAULT '',
    order_index  INT          DEFAULT 0,
    created_at   DATETIME     DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_learning_lesson_tut (tutorial_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学习教程章节正文';

-- ============================================================
-- 初始数据：默认租户（standalone 模式必需）
-- ============================================================
INSERT INTO tenants (id, code, name, status, created_at, updated_at)
VALUES (1, 'default', '默认租户', 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE status = 1;
