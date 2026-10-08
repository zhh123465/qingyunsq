ALTER TABLE ai_knowledge_bases ADD COLUMN qa_pair_count BIGINT NOT NULL DEFAULT 0 AFTER storage_bytes;
