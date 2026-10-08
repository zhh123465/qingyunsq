-- users 表添加 welcome_kb_seeded 标记，实现"欢迎知识库一次性种植"
-- 老策略：登录时检查用户是否有任何 KB，无则补种 → 用户删掉后会被重种
-- 新策略：只看 welcome_kb_seeded 标记，种过一次就永久标记，无论后来是否删除
--
-- 存量数据处理：所有现有用户统一标记为 seeded=1（保守策略）——
-- 我们无法从数据库区分"从未种过"与"种过后删掉"，为避免任何老用户被
-- 意外重种（可能被认为是烦扰），全部视为已种。全新注册用户走新分支即可。

ALTER TABLE users
  ADD COLUMN welcome_kb_seeded TINYINT NOT NULL DEFAULT 0
    COMMENT '欢迎知识库是否已种植过（一次性，用户删掉后不再重种）'
  AFTER tag_subscriptions;

UPDATE users SET welcome_kb_seeded = 1 WHERE deleted = 0;
