-- ============================================================
-- V3__seed_roles.sql
-- Khởi tạo dữ liệu vai trò (Roles) mặc định cho hệ thống
-- ============================================================

INSERT INTO role (name, description) VALUES ('ADMIN', 'Quản trị viên toàn quyền hệ thống')
ON DUPLICATE KEY UPDATE description = VALUES(description);

INSERT INTO role (name, description) VALUES ('STAFF', 'Nhân viên vận hành và xử lý đơn hàng')
ON DUPLICATE KEY UPDATE description = VALUES(description);

INSERT INTO role (name, description) VALUES ('CUSTOMER', 'Khách hàng mua sắm')
ON DUPLICATE KEY UPDATE description = VALUES(description);

INSERT INTO role (name, description) VALUES ('SELLER', 'Người bán / Đối tác')
ON DUPLICATE KEY UPDATE description = VALUES(description);

