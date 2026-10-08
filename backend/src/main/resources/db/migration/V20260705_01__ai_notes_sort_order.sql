-- Migration: Add sort_order column to ai_notes
-- Purpose: 让管理员在学习页调整公开笔记展示顺序（数值大靠前，0 = 未置顶按默认排序）
-- Date: 2026-07-05

ALTER TABLE ai_notes
    ADD COLUMN sort_order INT NOT NULL DEFAULT 0 COMMENT '展示权重（数值大靠前，管理员编辑排序时写入）' AFTER source_author;
