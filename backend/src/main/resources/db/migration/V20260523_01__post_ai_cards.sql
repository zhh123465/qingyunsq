-- ============================================================
-- 帖子智能卡片缓存表
-- ============================================================
-- 用途：为帖子详情页生成的「TL;DR + 适合谁读 + 价值类型 + 评论共识/争议」结构化摘要，
-- 通过 AI 生成（按需触发 + 缓存），避免每次浏览都调 LLM。
--
-- 失效策略：
-- 1) 帖子正文/标题更新 → 重新生成（按 post_version 检测）
-- 2) 评论数自上次生成后 +10 → 仅重新生成评论部分
-- 3) AI 调用失败 → 不写入，下次浏览再试
-- ============================================================

CREATE TABLE post_ai_cards (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL DEFAULT 1,
    post_id BIGINT NOT NULL,

    -- 正文卡片字段（依赖帖子内容）
    tldr VARCHAR(255) DEFAULT NULL COMMENT '一句话核心结论',
    audience VARCHAR(120) DEFAULT NULL COMMENT '适合谁读',
    value_type VARCHAR(20) DEFAULT NULL COMMENT '提问/经验/资源/吐槽/招募/讨论',
    read_minutes INT DEFAULT NULL COMMENT '估计阅读时长（分钟）',

    -- 评论卡片字段（依赖评论数据）
    comment_consensus VARCHAR(500) DEFAULT NULL COMMENT '高赞共识答案',
    comment_disputes VARCHAR(500) DEFAULT NULL COMMENT '主要争议点',

    -- 缓存元数据
    post_version BIGINT NOT NULL DEFAULT 0 COMMENT '生成时帖子 updated_at 时间戳（毫秒）',
    comment_count_snapshot INT NOT NULL DEFAULT 0 COMMENT '生成时的评论数',

    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,

    UNIQUE KEY uk_post_id (post_id),
    KEY idx_tenant_post (tenant_id, post_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='帖子 AI 智能卡片缓存';
