-- ============================================================
-- 裁撤智能体（Agents）与插件市场（Plugins）产品线（2026-07-12）
-- ============================================================
-- 背景：后端 48 端点从未被前端接入（api/ai-workspace.ts 无任何
-- /ai/agents、/ai/plugins 调用），按产品决策整体删除。
-- 同时清理 ai_conversations / ai_messages 中相关联的历史列，
-- 以及收藏表中 favorite_type='agent' 的残留行。
-- ============================================================

DROP TABLE IF EXISTS ai_agents;
DROP TABLE IF EXISTS ai_plugins;
DROP TABLE IF EXISTS ai_user_installed_plugins;

DELETE FROM ai_user_favorites WHERE favorite_type = 'agent';

ALTER TABLE ai_conversations
    DROP COLUMN agent_id,
    DROP COLUMN plugin_ids;

ALTER TABLE ai_messages
    DROP COLUMN agent_id,
    DROP COLUMN plugin_ids;
