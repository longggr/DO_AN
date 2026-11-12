-- ============================================================
-- V1__init_schema.sql
-- Khởi tạo cấu trúc Database cơ sở cho SmashHub
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- 1. ROLE
CREATE TABLE IF NOT EXISTS role (
    name VARCHAR(50) NOT NULL,
    description VARCHAR(255) NULL,
    PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. USERS
CREATE TABLE IF NOT EXISTS users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NULL,
    username VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL,
    password VARCHAR(255) NOT NULL,
    phone VARCHAR(30) NULL,
    role_name VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    auth_provider VARCHAR(20) NOT NULL DEFAULT 'LOCAL',
    email_verified_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username),
    UNIQUE KEY uk_users_email (email),
    KEY idx_users_role_name (role_name),
    CONSTRAINT fk_users_role FOREIGN KEY (role_name) REFERENCES role (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. PROVINCES (Tỉnh / Thành phố)
CREATE TABLE IF NOT EXISTS provinces (
    id INT NOT NULL,
    name VARCHAR(255) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4. DISTRICTS (Quận / Huyện)
CREATE TABLE IF NOT EXISTS districts (
    id INT NOT NULL,
    province_id INT NOT NULL,
    name VARCHAR(255) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_districts_province (province_id),
    CONSTRAINT fk_districts_province FOREIGN KEY (province_id) REFERENCES provinces (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5. WARDS (Phường / Xã)
CREATE TABLE IF NOT EXISTS wards (
    code VARCHAR(255) NOT NULL,
    district_id INT NOT NULL,
    name VARCHAR(255) NOT NULL,
    PRIMARY KEY (code),
    KEY idx_wards_district (district_id),
    CONSTRAINT fk_wards_district FOREIGN KEY (district_id) REFERENCES districts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 6. ADDRESSES (Sổ địa chỉ người dùng)
CREATE TABLE IF NOT EXISTS addresses (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NULL,
    user_id BIGINT NOT NULL,
    recipient_name VARCHAR(255) NOT NULL,
    phone VARCHAR(20) NULL,
    line1 VARCHAR(255) NOT NULL,
    province_id INT NOT NULL,
    district_id INT NOT NULL,
    ward_code VARCHAR(255) NOT NULL,
    primary_address VARCHAR(20) NOT NULL DEFAULT 'SECONDARY',
    PRIMARY KEY (id),
    KEY idx_addresses_user_id (user_id),
    KEY idx_addresses_province (province_id),
    KEY idx_addresses_district (district_id),
    KEY idx_addresses_ward (ward_code),
    CONSTRAINT fk_addresses_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_addresses_province FOREIGN KEY (province_id) REFERENCES provinces (id),
    CONSTRAINT fk_addresses_district FOREIGN KEY (district_id) REFERENCES districts (id),
    CONSTRAINT fk_addresses_ward FOREIGN KEY (ward_code) REFERENCES wards (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 7. BRAND (Thương hiệu)
CREATE TABLE IF NOT EXISTS brand (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NULL,
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(255) NOT NULL,
    logo_url VARCHAR(1024) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_brand_slug (slug),
    UNIQUE KEY uk_brand_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 8. CATEGORY (Danh mục có phân cấp cha/con)
CREATE TABLE IF NOT EXISTS category (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NULL,
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(255) NOT NULL,
    parent_id BIGINT NULL,
    display_order INT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_category_slug (slug),
    KEY idx_category_parent (parent_id),
    CONSTRAINT fk_category_parent FOREIGN KEY (parent_id) REFERENCES category (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 9. PRODUCT (Sản phẩm)
CREATE TABLE IF NOT EXISTS product (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NULL,
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    type VARCHAR(50) NOT NULL,
    category_id BIGINT NOT NULL,
    brand_id BIGINT NOT NULL,
    product_status VARCHAR(20) NOT NULL,
    price DECIMAL(10,2) NOT NULL,
    sale_price DECIMAL(19,2) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_slug (slug),
    KEY idx_product_category (category_id),
    KEY idx_product_brand (brand_id),
    CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES category (id),
    CONSTRAINT fk_product_brand FOREIGN KEY (brand_id) REFERENCES brand (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 10. PRODUCT_ATTRIBUTES (EAV)
CREATE TABLE IF NOT EXISTS product_attributes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    attr_name VARCHAR(100) NOT NULL,
    attr_value VARCHAR(255) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_product_attributes_product (product_id),
    KEY idx_product_attributes_name (attr_name),
    CONSTRAINT fk_product_attributes_product FOREIGN KEY (product_id) REFERENCES product (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 11. PRODUCT_COLORS (Màu sắc)
CREATE TABLE IF NOT EXISTS product_colors (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NULL,
    color_name VARCHAR(100) NOT NULL,
    hex_code VARCHAR(20) NULL,
    display_order INT NULL DEFAULT 0,
    product_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    KEY idx_product_colors_product (product_id),
    CONSTRAINT fk_product_colors_product FOREIGN KEY (product_id) REFERENCES product (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 12. PRODUCT_IMAGES (Hình ảnh sản phẩm)
CREATE TABLE IF NOT EXISTS product_images (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NULL,
    url VARCHAR(1024) NOT NULL,
    provider_public_id VARCHAR(255) NOT NULL,
    title VARCHAR(255) NULL,
    alt_text VARCHAR(512) NULL,
    is_main VARCHAR(20) NULL DEFAULT 'SECONDARY',
    order_index INT NULL DEFAULT 0,
    color_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    KEY idx_product_images_color (color_id),
    CONSTRAINT fk_product_images_color FOREIGN KEY (color_id) REFERENCES product_colors (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 13. PRODUCT_VARIANTS (Biến thể sản phẩm)
CREATE TABLE IF NOT EXISTS product_variants (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NULL,
    sku VARCHAR(100) NOT NULL,
    size VARCHAR(50) NULL,
    stock INT NOT NULL DEFAULT 0,
    active VARCHAR(20) NULL DEFAULT 'ACTIVE',
    inventory_status VARCHAR(20) NULL DEFAULT 'IN_STOCK',
    color_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_variants_sku (sku),
    KEY idx_product_variants_color (color_id),
    CONSTRAINT fk_product_variants_color FOREIGN KEY (color_id) REFERENCES product_colors (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 14. CART_ITEMS (Giỏ hàng người dùng)
CREATE TABLE IF NOT EXISTS cart_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NULL,
    user_id BIGINT NOT NULL,
    variant_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_variant (user_id, variant_id),
    KEY idx_cart_items_variant (variant_id),
    CONSTRAINT fk_cart_items_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_cart_items_variant FOREIGN KEY (variant_id) REFERENCES product_variants (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 15. ORDERS (Đơn hàng)
CREATE TABLE IF NOT EXISTS orders (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NULL,
    user_id BIGINT NOT NULL,
    order_status VARCHAR(20) NOT NULL DEFAULT 'PENDING_PAYMENT',
    shipping_method VARCHAR(15) NOT NULL DEFAULT 'STANDARD',
    shipping_recipient_name VARCHAR(255) NOT NULL,
    shipping_phone VARCHAR(20) NOT NULL,
    shipping_line1 VARCHAR(255) NOT NULL,
    shipping_province_id INT NOT NULL,
    shipping_district_id INT NOT NULL,
    shipping_ward_code VARCHAR(255) NOT NULL,
    subtotal DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    shipping_cost DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    discount DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    total DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    payment_method VARCHAR(20) NOT NULL,
    note VARCHAR(255) NULL,
    stock_restored BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    KEY idx_orders_user (user_id),
    KEY idx_orders_status (order_status),
    CONSTRAINT fk_orders_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_orders_province FOREIGN KEY (shipping_province_id) REFERENCES provinces (id),
    CONSTRAINT fk_orders_district FOREIGN KEY (shipping_district_id) REFERENCES districts (id),
    CONSTRAINT fk_orders_ward FOREIGN KEY (shipping_ward_code) REFERENCES wards (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 16. ORDER_ITEMS (Chi tiết món trong đơn hàng)
CREATE TABLE IF NOT EXISTS order_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NULL,
    order_id BIGINT NOT NULL,
    variant_id BIGINT NOT NULL,
    product_name VARCHAR(255) NOT NULL,
    sku VARCHAR(100) NOT NULL,
    unit_price DECIMAL(15,2) NOT NULL,
    quantity INT NOT NULL,
    total_price DECIMAL(15,2) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_order_items_order (order_id),
    KEY idx_order_items_variant (variant_id),
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE,
    CONSTRAINT fk_order_items_variant FOREIGN KEY (variant_id) REFERENCES product_variants (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 17. PAYMENT_TRANSACTION (Lịch sử giao dịch thanh toán)
CREATE TABLE IF NOT EXISTS payment_transaction (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NULL,
    order_id BIGINT NOT NULL,
    provider VARCHAR(255) NOT NULL,
    txn_ref VARCHAR(100) NOT NULL,
    amount DECIMAL(15,2) NOT NULL,
    status VARCHAR(255) NOT NULL,
    response_code VARCHAR(10) NULL,
    transaction_status VARCHAR(10) NULL,
    transaction_no VARCHAR(20) NULL,
    bank_code VARCHAR(20) NULL,
    pay_date DATETIME(6) NULL,
    expire_date DATETIME(6) NULL,
    ip_address VARCHAR(50) NULL,
    ipn_processed VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    raw_request_payload LONGTEXT NULL,
    raw_response_payload LONGTEXT NULL,
    failure_reason VARCHAR(255) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_txn_ref (txn_ref),
    KEY idx_payment_order (order_id),
    KEY idx_payment_status (status),
    CONSTRAINT fk_payment_transaction_order FOREIGN KEY (order_id) REFERENCES orders (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET FOREIGN_KEY_CHECKS = 1;

