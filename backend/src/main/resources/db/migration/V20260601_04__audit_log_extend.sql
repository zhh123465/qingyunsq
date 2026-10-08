-- ============================================================
-- 安全加固迁移 04：audit_logs 扩展 user_agent 列与 action+created_at 复合索引
-- ============================================================
-- 背景（bugfix.md 漏洞 26 / spec T9.2）：原 AuditLogService 通过 request-scope
-- HttpServletRequest 代理获取客户端信息，异步线程内会抛 IllegalStateException。
-- 新引入的 AuditContext 把 IP / UA 一并显式打包传入，因此审计表需要新增
-- user_agent 列以承载客户端 UA 维度。
--
-- 此外，许多事件溯源场景需要按"动作类型 + 时间"聚合（例如统计某段时间
-- PASSWORD_CHANGE 次数），原表仅有 idx_tenant_time / idx_operator，
-- 在按 action 过滤时会退化为全表扫描，新增复合索引避免该问题。
--
-- 执行步骤：
-- 1) 新增 user_agent 列（VARCHAR(255) NULL）
-- 2) 新增 (action, created_at) 复合索引
--
-- 兼容性：列与索引均为新增，对存量数据零侵入；线上回滚只需 DROP COLUMN /
-- DROP INDEX 即可。
-- ============================================================

ALTER TABLE audit_logs
    ADD COLUMN user_agent VARCHAR(255) NULL COMMENT '客户端 UA（含异步线程上下文，T9.2）'
    AFTER ip_address;

ALTER TABLE audit_logs
    ADD INDEX idx_audit_log_action_created (action, created_at);
