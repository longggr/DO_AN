-- ============================================================
-- V2__order_and_shipping_extensions.sql
-- Thêm bảng Shipments, OrderStatusHistory và các cột mở rộng cho Order/Address
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- 1. BẢNG SHIPMENTS (Quản lý vận đơn GHN)
CREATE TABLE IF NOT EXISTS shipments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NULL,
    order_id BIGINT NOT NULL,
    order_code VARCHAR(50) NULL,
    client_order_code VARCHAR(100) NOT NULL,
    service_id INT NULL,
    service_type_id INT NULL,
    fee DECIMAL(15,2) NULL,
    cod_amount DECIMAL(15,2) NULL,
    expected_delivery_date DATETIME(6) NULL,
    status VARCHAR(50) NULL,
    status_name VARCHAR(100) NULL,
    failure_reason VARCHAR(255) NULL,
    raw_response LONGTEXT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_shipments_order_id (order_id),
    UNIQUE KEY uk_shipments_order_code (order_code),
    KEY idx_shipments_client_order_code (client_order_code),
    CONSTRAINT fk_shipments_order FOREIGN KEY (order_id) REFERENCES orders (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. BẢNG ORDER_STATUS_HISTORY (Nhật ký biến động trạng thái đơn hàng)
CREATE TABLE IF NOT EXISTS order_status_history (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NULL,
    order_id BIGINT NOT NULL,
    from_status VARCHAR(20) NULL,
    to_status VARCHAR(20) NOT NULL,
    updated_by VARCHAR(100) NOT NULL,
    note VARCHAR(255) NULL,
    PRIMARY KEY (id),
    KEY idx_order_status_history_order (order_id),
    CONSTRAINT fk_order_status_history_order FOREIGN KEY (order_id) REFERENCES orders (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. ĐẢM BẢO CỘT MỞ RỘNG TỒN TẠI TRÊN BẢNG ADDRESSES VÀ ORDERS (Safe conditional DDL)
DROP PROCEDURE IF EXISTS add_order_and_address_columns;
DELIMITER $$
CREATE PROCEDURE add_order_and_address_columns()
BEGIN
    -- Thêm phone cho addresses nếu chưa có
    IF NOT EXISTS (
        SELECT * FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'addresses' AND COLUMN_NAME = 'phone'
    ) THEN
        ALTER TABLE addresses ADD COLUMN phone VARCHAR(20) NULL AFTER recipient_name;
    END IF;

    -- Thêm note cho orders nếu chưa có
    IF NOT EXISTS (
        SELECT * FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'orders' AND COLUMN_NAME = 'note'
    ) THEN
        ALTER TABLE orders ADD COLUMN note VARCHAR(255) NULL;
    END IF;

    -- Thêm stock_restored cho orders nếu chưa có
    IF NOT EXISTS (
        SELECT * FROM information_schema.COLUMNS 
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'orders' AND COLUMN_NAME = 'stock_restored'
    ) THEN
        ALTER TABLE orders ADD COLUMN stock_restored BOOLEAN NOT NULL DEFAULT FALSE;
    END IF;
END $$
DELIMITER ;

CALL add_order_and_address_columns();
DROP PROCEDURE IF EXISTS add_order_and_address_columns;

SET FOREIGN_KEY_CHECKS = 1;

