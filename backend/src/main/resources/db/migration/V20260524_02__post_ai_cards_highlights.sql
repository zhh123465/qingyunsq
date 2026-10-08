-- ============================================================
-- post_ai_cards 增加 AI 提取重点字段
-- ============================================================
-- AI 在生成卡片时同步抽取 2-3 个核心关键词，列表用色块标签呈现。
-- 存为 JSON 数组（mysql VARCHAR + 应用层 json 序列化），避免再建表。
-- ============================================================

ALTER TABLE post_ai_cards
    ADD COLUMN highlights VARCHAR(400) DEFAULT NULL COMMENT 'AI 提取重点，JSON 数组' AFTER hot_comment_excerpt;
