ALTER TABLE learning_lessons ADD COLUMN content_oss_key VARCHAR(255) DEFAULT NULL AFTER content;
ALTER TABLE ai_notes ADD COLUMN content_oss_key VARCHAR(255) DEFAULT NULL AFTER content;
ALTER TABLE ai_kb_documents ADD COLUMN storage_key VARCHAR(255) DEFAULT NULL AFTER file_size;
