ALTER TABLE users
  ADD COLUMN qq_openid VARCHAR(64) DEFAULT NULL COMMENT 'QQ openid' AFTER wechat_unionid,
  ADD COLUMN github_id VARCHAR(64) DEFAULT NULL COMMENT 'GitHub 用户 id' AFTER qq_openid,
  ADD UNIQUE KEY uk_tenant_qq_openid (tenant_id, qq_openid),
  ADD UNIQUE KEY uk_tenant_github_id (tenant_id, github_id);
