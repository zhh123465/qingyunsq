-- ============================================================
-- 安全加固迁移 05：resources.file_md5 标记为 @Deprecated
-- ============================================================
-- 背景（bugfix.md 漏洞 6 / spec T8.10）：T4.x 任务已经把所有上传链路切到
-- DigestInputStream + SHA-256（resources.file_sha256），但旧数据仍然只有
-- file_md5。直接 DROP 会让历史去重 / 校验逻辑炸掉，因此本期仅做"标记淘汰"：
--   * 列改为 NULL，并在 COMMENT 中明确 "@Deprecated - 保留至历史数据 100% 迁移到 file_sha256"；
--   * 不删除 idx_md5 索引（仍有读链路依赖 MD5 做老资源命中查询）；
--   * 真正 DROP 计划留到下一个大版本（待运维确认 file_sha256 回填率 = 100%）。
--
-- 兼容性：仅 MODIFY COLUMN 注释 + NULL 化，不影响现有读写；旧上传客户端如
-- 仍在写 file_md5 不会失败。
-- ============================================================

ALTER TABLE resources
    MODIFY COLUMN file_md5 VARCHAR(64) NULL
        COMMENT '@Deprecated - 保留至历史数据 100% 迁移到 file_sha256（spec T8.10）';
