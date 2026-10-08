-- 2026-07-02: ai_notes 增加"出处 / 友链"三字段，用于同步同学（外部）笔记进本站学习笔记流。
-- source_url    : 外部原文 URL（详情页 banner 跳转、卡片外链角标依据）
-- source_name   : 原站显示名（如 "cnblogs.com/LFmin"、"LFmin/期末"）
-- source_author : 原作者显示名（如 "LFmin"）
--
-- 空串代表"本站原生笔记"，行为完全不变；非空的笔记会在学习笔记卡与详情页显示出处 banner。

ALTER TABLE ai_notes
    ADD COLUMN source_url    VARCHAR(512) NOT NULL DEFAULT '' COMMENT '外部原文 URL',
    ADD COLUMN source_name   VARCHAR(128) NOT NULL DEFAULT '' COMMENT '原站显示名',
    ADD COLUMN source_author VARCHAR(128) NOT NULL DEFAULT '' COMMENT '原作者显示名',
    ADD INDEX idx_ai_notes_source_url (source_url);
