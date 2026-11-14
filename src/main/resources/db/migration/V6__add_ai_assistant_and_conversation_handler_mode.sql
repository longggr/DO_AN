-- ============================================================
-- V6__add_ai_assistant_and_conversation_handler_mode.sql
-- Thêm Trợ lý AI và quản lý chế độ xử lý (AI vs HUMAN) cho SmashHub Chat
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- 1. Bổ sung cột handler_mode vào bảng conversations
ALTER TABLE conversations
    ADD COLUMN handler_mode VARCHAR(20) NOT NULL DEFAULT 'AI' AFTER status;

CREATE INDEX idx_conversations_handler_mode_status ON conversations (handler_mode, status);

-- 2. Bảng cache câu hỏi thường gặp (FAQ) đã chuẩn hóa để fallback khi Gemini nghẽn
CREATE TABLE IF NOT EXISTS ai_faq_entries (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NULL,
    question_hash VARCHAR(64) NOT NULL,
    normalized_question VARCHAR(500) NOT NULL,
    answer TEXT NOT NULL,
    category VARCHAR(50) NOT NULL DEFAULT 'GENERAL',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    usage_count BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_faq_question_hash (question_hash),
    KEY idx_faq_active_category (is_active, category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. Bảng lưu trữ chunks kiến thức / catalog phục vụ RAG và so sánh hash
CREATE TABLE IF NOT EXISTS ai_knowledge_chunks (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NULL,
    entity_type VARCHAR(30) NOT NULL, -- PRODUCT, FAQ, POLICY, BRAND
    entity_id BIGINT NULL,
    content_hash VARCHAR(64) NOT NULL,
    chunk_text TEXT NOT NULL,
    embedding_json MEDIUMTEXT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    PRIMARY KEY (id),
    UNIQUE KEY uk_chunk_entity_hash (entity_type, entity_id, content_hash),
    KEY idx_chunk_entity (entity_type, entity_id),
    KEY idx_chunk_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4. Bảng ghi nhận chi phí và token tiêu thụ theo ngày của Gemini
CREATE TABLE IF NOT EXISTS ai_cost_logs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NULL,
    log_date DATE NOT NULL,
    model_name VARCHAR(50) NOT NULL,
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    estimated_cost_usd DECIMAL(12, 6) NOT NULL DEFAULT 0.000000,
    request_count INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cost_date_model (log_date, model_name),
    KEY idx_cost_date (log_date DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Thêm một số FAQ mặc định cho shop cầu lông SmashHub
INSERT INTO ai_faq_entries (created_at, updated_at, question_hash, normalized_question, answer, category, is_active, usage_count)
VALUES
(NOW(), NOW(), '8c6976e5b5410415bde908bd4dee15dfb167a9c873fc4bb8a81f6f2ab448a918', 'chinh sach doi tra', 'SmashHub hỗ trợ đổi hàng trong vòng 7 ngày kể từ khi nhận hàng đối với sản phẩm còn nguyên tem mác, chưa qua sử dụng hoặc căng cước (đối với khung vợt). Trường hợp lỗi do nhà sản xuất sẽ được đổi mới 100%.', 'POLICY', TRUE, 0),
(NOW(), NOW(), '04f057863bc0d9894e6febeba74b41a7d6ab2401614e21ab1713289069d51f98', 'thoi gian giao hang bao lau', 'Đơn hàng nội thành TP.HCM và Hà Nội nhận hàng trong 1-2 ngày. Các tỉnh thành khác giao qua GHN từ 2-4 ngày làm việc.', 'SHIPPING', TRUE, 0),
(NOW(), NOW(), 'f9116e033d284f18370f5e135bc78cb276d4df9253457db2dffdc4fb2193b2d6', 'shop co cang cuoc vot khong', 'SmashHub có hỗ trợ căng cước sẵn theo số kg yêu cầu (Yonex BG65, BG65Ti, Nanogy 95, Lining No.1...). Bạn vui lòng ghi chú số kg căng cước khi đặt hàng hoặc nhắn tin số kg tại đây nhé!', 'SERVICE', TRUE, 0);

SET FOREIGN_KEY_CHECKS = 1;

