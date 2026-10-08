-- Migration: Create ai_notes table
-- Date: 2026-06-28
-- Description: Standalone notes entity for markdown notes and file-based notes

CREATE TABLE IF NOT EXISTS ai_notes (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    tenant_id BIGINT NOT NULL DEFAULT 0,
    title VARCHAR(255) NOT NULL DEFAULT '',
    content MEDIUMTEXT,
    content_type VARCHAR(32) DEFAULT 'markdown',
    tags JSON,
    status VARCHAR(32) DEFAULT 'draft',
    view_count BIGINT DEFAULT 0,
    owner_id BIGINT NOT NULL DEFAULT 0,
    knowledge_base_id VARCHAR(32) DEFAULT NULL,
    deleted TINYINT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_ai_notes_owner (owner_id),
    INDEX idx_ai_notes_status (status),
    INDEX idx_ai_notes_kb (knowledge_base_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
