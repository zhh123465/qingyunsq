-- ============================================================
-- V20260624_02__ai_workspace_tables.sql
-- AI 工作台持久化：替代 backend/data/ai-workspace.json 内存存储，
-- 将智能体/插件/知识库/会话迁移到 MySQL，纳入租户隔离和事务。
-- Flyway 自动执行，与 schema.sql 保持同步。
-- ============================================================

CREATE TABLE IF NOT EXISTS ai_agents (
    id          VARCHAR(32)  NOT NULL PRIMARY KEY COMMENT 'agent_xxx',
    tenant_id   BIGINT UNSIGNED NOT NULL DEFAULT 1,
    name        VARCHAR(128) NOT NULL DEFAULT 'Untitled Agent',
    description TEXT,
    category    VARCHAR(64)  DEFAULT 'General',
    model       VARCHAR(128) NOT NULL DEFAULT 'deepseek-v4-flash',
    prompt      TEXT,
    abilities   JSON         DEFAULT NULL COMMENT '能力标签数组',
    tags        JSON         DEFAULT NULL COMMENT '标签数组',
    avatar      VARCHAR(255) DEFAULT '',
    color       VARCHAR(16)  DEFAULT '#18c7a7',
    user_count  BIGINT       NOT NULL DEFAULT 0,
    rating      DOUBLE       NOT NULL DEFAULT 5.0,
    owner_id    BIGINT       NOT NULL DEFAULT 0,
    -- JSON 列存 ID 数组，避免多对多关联表增加复杂度
    knowledge_base_ids JSON DEFAULT NULL,
    plugin_ids         JSON DEFAULT NULL,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_ai_agents_tenant (tenant_id),
    INDEX idx_ai_agents_category (tenant_id, category),
    INDEX idx_ai_agents_owner (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 智能体';

CREATE TABLE IF NOT EXISTS ai_plugins (
    id           VARCHAR(32)  NOT NULL PRIMARY KEY COMMENT 'plugin_xxx',
    tenant_id    BIGINT UNSIGNED NOT NULL DEFAULT 1,
    name         VARCHAR(128) NOT NULL DEFAULT 'Untitled Plugin',
    description  TEXT,
    category     VARCHAR(64)  DEFAULT 'Productivity',
    icon         VARCHAR(255) DEFAULT '',
    color        VARCHAR(16)  DEFAULT '#38bdf8',
    usage_count  BIGINT       NOT NULL DEFAULT 0,
    install_count BIGINT      NOT NULL DEFAULT 0,
    rating       DOUBLE       NOT NULL DEFAULT 5.0,
    is_official  TINYINT      NOT NULL DEFAULT 0,
    is_featured  TINYINT      NOT NULL DEFAULT 0,
    permissions  JSON         DEFAULT NULL COMMENT '所需权限数组',
    input_schema  JSON        DEFAULT NULL,
    output_schema JSON        DEFAULT NULL,
    endpoint     VARCHAR(512) DEFAULT '',
    owner_id     BIGINT       NOT NULL DEFAULT 0,
    review_status VARCHAR(32) DEFAULT 'pending',
    deleted      TINYINT      NOT NULL DEFAULT 0,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ai_plugins_tenant (tenant_id),
    INDEX idx_ai_plugins_category (tenant_id, category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 插件';

CREATE TABLE IF NOT EXISTS ai_knowledge_bases (
    id             VARCHAR(32)  NOT NULL PRIMARY KEY COMMENT 'kb_xxx',
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
    INDEX idx_ai_kb_tenant (tenant_id),
    INDEX idx_ai_kb_owner (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 知识库';

CREATE TABLE IF NOT EXISTS ai_kb_documents (
    id               VARCHAR(32)  NOT NULL PRIMARY KEY COMMENT 'doc_xxx',
    knowledge_base_id VARCHAR(32) NOT NULL,
    file_name        VARCHAR(255),
    file_size        BIGINT       NOT NULL DEFAULT 0,
    tags             JSON         DEFAULT NULL,
    parse_mode       VARCHAR(32)  DEFAULT 'auto',
    status           VARCHAR(32)  DEFAULT 'ready',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ai_docs_kb (knowledge_base_id),
    FOREIGN KEY (knowledge_base_id) REFERENCES ai_knowledge_bases(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 知识库文档';

CREATE TABLE IF NOT EXISTS ai_kb_qa_pairs (
    id               VARCHAR(32) NOT NULL PRIMARY KEY COMMENT 'qa_xxx',
    knowledge_base_id VARCHAR(32) NOT NULL,
    question         TEXT,
    answer           TEXT,
    tags             JSON        DEFAULT NULL,
    created_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ai_qa_kb (knowledge_base_id),
    FOREIGN KEY (knowledge_base_id) REFERENCES ai_knowledge_bases(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 知识库问答对';

CREATE TABLE IF NOT EXISTS ai_ingest_tasks (
    task_id          VARCHAR(32) NOT NULL PRIMARY KEY COMMENT 'task_xxx',
    knowledge_base_id VARCHAR(32) NOT NULL,
    status           VARCHAR(32) DEFAULT 'pending',
    progress         INT         DEFAULT 0,
    message          VARCHAR(512),
    INDEX idx_ai_ingest_kb (knowledge_base_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 文档解析任务';

CREATE TABLE IF NOT EXISTS ai_conversations (
    id        VARCHAR(32)  NOT NULL PRIMARY KEY COMMENT 'chat_xxx',
    tenant_id BIGINT UNSIGNED NOT NULL DEFAULT 1,
    title     VARCHAR(255) NOT NULL DEFAULT 'New conversation',
    model     VARCHAR(128) NOT NULL DEFAULT 'deepseek-v4-flash',
    agent_id  VARCHAR(32)  DEFAULT '',
    plugin_ids         JSON DEFAULT NULL,
    knowledge_base_ids JSON DEFAULT NULL,
    owner_id  BIGINT       NOT NULL,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_ai_conv_owner (owner_id),
    INDEX idx_ai_conv_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 对话会话';

CREATE TABLE IF NOT EXISTS ai_messages (
    id              VARCHAR(32)  NOT NULL PRIMARY KEY COMMENT 'msg_xxx',
    conversation_id VARCHAR(32)  NOT NULL,
    role            VARCHAR(16)  NOT NULL COMMENT 'user | assistant',
    content         MEDIUMTEXT,
    model           VARCHAR(128) DEFAULT '',
    agent_id        VARCHAR(32)  DEFAULT '',
    plugin_ids      JSON         DEFAULT NULL,
    knowledge_base_ids JSON      DEFAULT NULL,
    attachments     JSON         DEFAULT NULL,
    feedback        JSON         DEFAULT NULL COMMENT '{helpful,reason,createdAt}',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ai_msg_conv (conversation_id),
    FOREIGN KEY (conversation_id) REFERENCES ai_conversations(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 对话消息';

CREATE TABLE IF NOT EXISTS ai_user_favorites (
    user_id       BIGINT       NOT NULL,
    favorite_type VARCHAR(32)  NOT NULL COMMENT 'agent | knowledge_base',
    target_id     VARCHAR(32)  NOT NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, favorite_type, target_id),
    INDEX idx_ai_fav_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 收藏关系';

CREATE TABLE IF NOT EXISTS ai_user_installed_plugins (
    user_id    BIGINT      NOT NULL,
    plugin_id  VARCHAR(32) NOT NULL,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, plugin_id),
    INDEX idx_ai_inst_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 插件安装关系';

-- ===== 种子数据（与 AiWorkspaceService.seed() 一致）=====
INSERT IGNORE INTO ai_agents (id, name, description, category, tags, color, model, prompt, owner_id)
VALUES
('agent_001', 'Writing Assistant', 'Drafts reports, summaries and polished copy.', 'General', '["Writing"]', '#18c7a7', 'deepseek-v4-flash', 'You are a concise writing assistant.', 0),
('agent_002', 'Study Planner', 'Turns goals into weekly learning plans.', 'Learning', '["Planning"]', '#3b82f6', 'mimo-v2.5', 'You help students make practical study plans.', 0);

INSERT IGNORE INTO ai_plugins (id, name, description, category, color, is_official, is_featured, permissions, review_status)
VALUES
('plugin_weather', 'Weather Lookup', 'Gets weather-style structured information.', 'Life Services', '#38bdf8', 1, 1, '["network"]', 'approved'),
('plugin_translate', 'Translator', 'Translates short text between Chinese and English.', 'AI Capability', '#a855f7', 1, 1, '[]', 'approved'),
('plugin_pdf', 'PDF Helper', 'Extracts and summarizes PDF-like document content.', 'Productivity', '#f59e0b', 0, 1, '["file"]', 'approved');

-- 知识库种子数据已移除