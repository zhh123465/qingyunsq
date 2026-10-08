-- Migration: Create learning_tutorials + learning_lessons tables
-- Date: 2026-06-29
-- Description: Globally shared tutorial content crawled from public free sources (e.g. 菜鸟教程).
--              Not tenant-scoped — registered in MyBatisPlusConfig.TENANT_IGNORE_TABLES.

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
