-- ============================================================
-- V5__create_chat_tables.sql
-- Khởi tạo cấu trúc Database cho module Real-time Chat SmashHub
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- 1. CONVERSATIONS (Hội thoại tư vấn giữa Khách hàng và Admin/Staff)
CREATE TABLE IF NOT EXISTS conversations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NULL,
    customer_id BIGINT NOT NULL,
    assigned_admin_id BIGINT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    last_message_preview VARCHAR(255) NULL,
    last_message_at DATETIME(6) NULL,
    customer_unread_count INT NOT NULL DEFAULT 0,
    admin_unread_count INT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_conversation_customer (customer_id),
    KEY idx_conversation_last_msg (last_message_at DESC),
    KEY idx_conversation_status_last_msg (status, last_message_at DESC),
    KEY idx_conversation_admin_unread (admin_unread_count, last_message_at DESC),
    CONSTRAINT fk_conversation_customer FOREIGN KEY (customer_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_conversation_admin FOREIGN KEY (assigned_admin_id) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. CHAT_MESSAGES (Tin nhắn trao đổi trong hội thoại)
CREATE TABLE IF NOT EXISTS chat_messages (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NULL,
    conversation_id BIGINT NOT NULL,
    sender_id BIGINT NOT NULL,
    sender_role VARCHAR(20) NOT NULL,
    content TEXT NULL,
    message_type VARCHAR(30) NOT NULL,
    attachment_url VARCHAR(1024) NULL,
    referenced_product_id BIGINT NULL,
    referenced_order_id BIGINT NULL,
    snapshot_json TEXT NULL,
    client_message_id VARCHAR(64) NOT NULL,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    UNIQUE KEY uk_chat_messages_client_id (conversation_id, client_message_id),
    KEY idx_chat_messages_conv_created (conversation_id, created_at DESC),
    KEY idx_chat_messages_unread_role (conversation_id, is_read, sender_role),
    CONSTRAINT fk_chat_messages_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE,
    CONSTRAINT fk_chat_messages_sender FOREIGN KEY (sender_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_chat_messages_product FOREIGN KEY (referenced_product_id) REFERENCES product (id) ON DELETE SET NULL,
    CONSTRAINT fk_chat_messages_order FOREIGN KEY (referenced_order_id) REFERENCES orders (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET FOREIGN_KEY_CHECKS = 1;

