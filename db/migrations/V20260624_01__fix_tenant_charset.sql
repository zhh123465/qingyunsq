-- ============================================================
-- V20260624_01__fix_tenant_charset.sql
-- 目的：修复存量库中 tenants 表中文名称双重编码乱码
--       （典型表现：管理后台「租户管理」页面租户名称显示为 é»˜è®¤ç§Ÿæˆ·）。
-- 验证：已在生产环境（2026-06-24）执行并确认还原为「默认租户」。
--
-- 根因：Docker MySQL init 阶段，mysql 客户端以 latin1（即 cp1252，下同）连接执行
--       V1__bootstrap_default_tenant.sql 中的 INSERT。MySQL 将客户端发来的 UTF-8 字节
--       按 cp1252 单字节字符解释，再编码为 UTF-8 存入 utf8mb4 列，形成双重编码。
--       而 Java 应用通过 JDBC URL 中 characterEncoding=UTF-8 写入的数据不受影响，
--       因此仅 tenants 初始种子数据乱码，users/posts/spaces 等表中文均正常。
--
-- 修复手法：CONVERT(col USING latin1) 将当前存储的 UTF-8 字节反向映射回原始 cp1252
--       单字节流 → CAST AS BINARY 剥离字符集语义 → CONVERT USING utf8mb4 将字节流
--       重新解释为 UTF-8，得到正确中文。
--       对本就正确的数据是幂等无害的（latin1→binary→utf8mb4 往返不变）。
--
-- 应用方式（表已是 utf8mb4，连接也需要 utf8mb4 以避免文末中文字面量二次乱码）：
--   mysql --default-character-set=utf8mb4 -u "$MYSQL_USER" -p"$MYSQL_PASSWORD" campus_forum \
--     < db/migrations/V20260624_01__fix_tenant_charset.sql
-- ============================================================

-- 核心：修复 tenants 表中文文本列
UPDATE tenants SET name         = CONVERT(CAST(CONVERT(name         USING latin1) AS BINARY) USING utf8mb4);
UPDATE tenants SET announcement = CONVERT(CAST(CONVERT(announcement USING latin1) AS BINARY) USING utf8mb4)
  WHERE announcement IS NOT NULL;

-- 兜底：若原始数据在写入时已被截断为 '?'（真实字节丢失），上述 UPDATE 无法还原，
--       此处把 id=1 的默认租户名称硬纠正。对已还原的行此句幂等（值不变）。
UPDATE tenants SET name = '默认租户' WHERE id = 1 AND name <> '默认租户';

-- 可选：记录迁移已应用
-- INSERT INTO schema_migrations(version) VALUES ('V20260624_01')
--   ON DUPLICATE KEY UPDATE version = version;
