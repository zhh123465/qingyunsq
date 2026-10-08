ALTER TABLE users
  ADD COLUMN wechat_openid VARCHAR(64) DEFAULT NULL COMMENT '微信小程序 openid' AFTER avatar_url,
  ADD COLUMN wechat_unionid VARCHAR(64) DEFAULT NULL COMMENT '微信开放平台 unionid' AFTER wechat_openid,
  ADD UNIQUE KEY uk_tenant_wechat_openid (tenant_id, wechat_openid);
