-- ============================================================
-- 安全加固迁移 01：reset_token 改为 SHA-256 哈希存储
-- ============================================================
-- 背景：密码重置令牌原本以明文存储，数据库泄漏即可重置任意账号。
-- 修复后令牌存为 SHA-256 hex（固定 64 字符），明文仅在邮件中下发。
--
-- 执行步骤：
-- 1) 强制现存所有令牌失效（避免明文 / 哈希混存）
-- 2) 列长度从 VARCHAR(128) 缩短为 VARCHAR(64)
-- 影响：所有"忘记密码"流程中已发但未使用的链接全部失效，需要用户重新申请。
-- ============================================================

UPDATE users
SET reset_token = NULL,
    reset_token_expires = NULL
WHERE reset_token IS NOT NULL;

ALTER TABLE users
    MODIFY COLUMN reset_token VARCHAR(64) DEFAULT NULL COMMENT '密码重置令牌 SHA-256 哈希（hex）';
