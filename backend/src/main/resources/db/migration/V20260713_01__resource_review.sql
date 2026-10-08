-- 资源上传审核流（2026-07-13）
-- status 语义扩展：0=隐藏（管理员下架，语义不变） 1=已发布（语义不变） 2=待审核（新上传默认） 3=已驳回
-- 默认值改为 2：即使绕过应用层直接 INSERT，也不会默认发布
-- 存量数据全部为 status=1（已发布），不需要回填
ALTER TABLE resources
    MODIFY COLUMN status TINYINT NOT NULL DEFAULT 2 COMMENT '0=隐藏 1=已发布 2=待审核 3=已驳回',
    ADD COLUMN review_reason VARCHAR(255) DEFAULT NULL COMMENT '驳回原因（status=3 时有值）' AFTER status,
    ADD COLUMN reviewed_by   BIGINT UNSIGNED DEFAULT NULL COMMENT '审核人用户 ID' AFTER review_reason,
    ADD COLUMN reviewed_at   DATETIME DEFAULT NULL COMMENT '审核时间' AFTER reviewed_by,
    ADD KEY idx_resources_tenant_status (tenant_id, status);
