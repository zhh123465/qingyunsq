-- ============================================================
-- 知识库真实 RAG：文档切块表（2026-07-12）
-- ============================================================
-- 文档上传时抽取正文并按段落切块入库（KbRagService），
-- 对话挂载知识库时按关键词相关度检索 top-N 切块注入 prompt，
-- 替代旧的"只拼知识库名称"占位实现。
-- ============================================================

CREATE TABLE IF NOT EXISTS ai_kb_chunks (
    id                BIGINT       NOT NULL PRIMARY KEY AUTO_INCREMENT,
    knowledge_base_id VARCHAR(32)  NOT NULL,
    document_id       VARCHAR(32)  NOT NULL,
    chunk_index       INT          NOT NULL DEFAULT 0,
    content           TEXT         NOT NULL,
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ai_chunks_kb (knowledge_base_id),
    INDEX idx_ai_chunks_doc (document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 知识库文档切块（RAG 检索单元）';
