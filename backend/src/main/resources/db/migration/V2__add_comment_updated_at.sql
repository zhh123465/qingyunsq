-- 评论编辑功能：添加 updated_at 字段
ALTER TABLE comments ADD COLUMN updated_at DATETIME DEFAULT NULL COMMENT '最后编辑时间' AFTER created_at;
