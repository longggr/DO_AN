-- ============================================================
-- V7__phase1_rag_knowledge_and_bot_feedback.sql
-- Giai đoạn 1: Bổ sung BOT metadata, Feedback và Bảng tài liệu tri thức RAG
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- 1. Bổ sung các cột AI metadata và Feedback vào bảng chat_messages
ALTER TABLE chat_messages
    ADD COLUMN ai_model VARCHAR(50) NULL AFTER client_message_id,
    ADD COLUMN ai_sources TEXT NULL AFTER ai_model,
    ADD COLUMN ai_feedback VARCHAR(20) NULL AFTER ai_sources;

CREATE INDEX idx_chat_messages_feedback ON chat_messages (ai_feedback);

-- 2. Bảng lưu trữ tài liệu tri thức tĩnh (Chính sách, FAQ, Hướng dẫn chọn vợt/cước)
CREATE TABLE IF NOT EXISTS knowledge_document (
    id BIGINT NOT NULL AUTO_INCREMENT,
    title VARCHAR(255) NOT NULL,
    category VARCHAR(50) NOT NULL DEFAULT 'GENERAL',
    content_markdown MEDIUMTEXT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    KEY idx_doc_category (category),
    KEY idx_doc_status (status),
    KEY idx_doc_hash (content_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET FOREIGN_KEY_CHECKS = 1;

