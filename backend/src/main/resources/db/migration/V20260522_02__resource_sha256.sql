-- ============================================================
-- 安全加固迁移 02：resources 表新增 file_sha256 字段
-- ============================================================
-- 背景：原 file_md5 用 MD5 做去重，MD5 抗碰撞失效。
-- 切换为 SHA-256，过渡期同时保留 file_md5 不删除，避免单次部署期数据不一致。
--
-- 执行步骤：
-- 1) 新增 file_sha256 列（VARCHAR(64) 存 hex）
-- 2) 建立查询索引
-- 后续：file_md5 列在下个版本（迁移 V2.0+）中删除
-- ============================================================

ALTER TABLE resources
    ADD COLUMN file_sha256 VARCHAR(64) DEFAULT NULL COMMENT 'SHA-256 hex 指纹' AFTER file_md5;

CREATE INDEX idx_resources_file_sha256 ON resources(file_sha256);
