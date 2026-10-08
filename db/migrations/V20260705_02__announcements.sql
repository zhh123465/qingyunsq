-- Migration: Create announcements table
-- Date: 2026-07-05
-- Description: 系统公告表，支持多租户隔离；管理员发布，前台横幅 + 独立列表页展示

CREATE TABLE IF NOT EXISTS announcements (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统公告';
