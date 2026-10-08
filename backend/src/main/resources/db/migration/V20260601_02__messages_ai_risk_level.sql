-- ============================================================
-- 安全加固迁移 02：messages 表新增 ai_risk_level 字段
-- ============================================================
-- 背景（bugfix.md 漏洞 16 / spec T8.10）：私信内容此前仅做 HTML 净化，
-- 没有任何"违规等级"打标，导致：
--   1) 监管侧无法在审计后台快速圈选高风险消息（举报响应慢）；
--   2) 风控侧无法根据等级做差异化处理（例如 level=2 直接拦截或转人工）；
--   3) 无法回溯统计某段时间内敏感消息发送量。
--
-- 本次扩展把 SensitiveWordService.getRiskLevel(...) 在 MessageService#send
-- 中产出的等级（0=安全 / 1=疑似 / 2=违规）落库为 messages.ai_risk_level，
-- 后台风控可基于此列做筛选 / 报表 / 自动处置。
--
-- 兼容性：
--   * 列默认值为 0，存量私信不会被错判为"违规"；
--   * 仅 ADD COLUMN，不影响读路径；回滚只需 DROP COLUMN ai_risk_level。
-- ============================================================

ALTER TABLE messages
    ADD COLUMN ai_risk_level TINYINT NOT NULL DEFAULT 0
        COMMENT '0=安全 1=疑似 2=违规（来自 SensitiveWordService.getRiskLevel）'
    AFTER image_url;
