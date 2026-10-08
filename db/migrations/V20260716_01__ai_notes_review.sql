-- 笔记发布审核流（2026-07-16），对齐资源审核流（V20260713_01）
-- status（VARCHAR(32)）语义扩展：draft=草稿 pending=待审核（新增） published=已发布 rejected=已驳回（新增） hidden=管理员隐藏
-- 普通用户点"发布"落 pending，管理员审核通过才变 published；已发布笔记被作者编辑标题/正文后自动打回 pending 重审
-- 存量数据全部为 draft/published/hidden，无需回填
ALTER TABLE ai_notes
    ADD COLUMN review_reason VARCHAR(255) DEFAULT NULL COMMENT '驳回原因（status=rejected 时有值）' AFTER status,
    ADD COLUMN reviewed_by   BIGINT UNSIGNED DEFAULT NULL COMMENT '审核人用户 ID' AFTER review_reason,
    ADD COLUMN reviewed_at   DATETIME DEFAULT NULL COMMENT '审核时间' AFTER reviewed_by;
