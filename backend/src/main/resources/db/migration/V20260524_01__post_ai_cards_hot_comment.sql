-- ============================================================
-- post_ai_cards 增加热门评论字段
-- ============================================================
-- 列表页帖子卡片下方展示「💬 热门评论」一行，可点击跳转到详情页对应评论。
-- 取数策略：当前帖子下点赞最多的一条评论（无评论时为 NULL）。
-- 与 TL;DR 一同在 PostAiCardService.getOrGenerate 中刷新。
-- ============================================================

ALTER TABLE post_ai_cards
    ADD COLUMN hot_comment_id BIGINT DEFAULT NULL COMMENT '点赞最多的评论 ID（用于跳转锚点）' AFTER comment_disputes,
    ADD COLUMN hot_comment_excerpt VARCHAR(200) DEFAULT NULL COMMENT '热门评论摘录（截断到 80 字）' AFTER hot_comment_id;
