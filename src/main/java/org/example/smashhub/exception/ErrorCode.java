package org.example.smashhub.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

@Getter
public enum ErrorCode {
    UNCATEGORIZED_EXCEPTION(9999, "Uncategorized error", HttpStatus.INTERNAL_SERVER_ERROR),
    INVALID_KEY(1001, "Uncategorized error", HttpStatus.BAD_REQUEST),
    USER_EXISTED(1002, "User existed", HttpStatus.BAD_REQUEST),
    USERNAME_INVALID(1003, "Username must be at least 3 characters", HttpStatus.BAD_REQUEST),
    INVALID_PASSWORD(1004, "Password must be at least 8 characters", HttpStatus.BAD_REQUEST),
    USER_NOT_EXISTED(1005, "User not existed", HttpStatus.NOT_FOUND),
    UNAUTHENTICATED(1006, "Unauthenticated", HttpStatus.UNAUTHORIZED),
    UNAUTHORIZED(1007, "You do not have permission", HttpStatus.FORBIDDEN),
    INVALID_DOB(1008, "Your age must be at least {min}", HttpStatus.BAD_REQUEST),
    ACCOUNT_LOCKED(1009, "Your account has been locked.Please contact the Admin.", HttpStatus.FORBIDDEN),
    INCORRECT_PASSWORD(1010, "Password incorrect", HttpStatus.NOT_FOUND),
    PASSWORD_ATTEMPT_EXCEEDED(1011, "Incorrect password entered too many times. Your account has been locked for %d seconds.", HttpStatus.TOO_MANY_REQUESTS),
    EMAIL_NOT_EXISTED(1012, "Please provide an valid email!", HttpStatus.NOT_FOUND),
    PASSWORD_CONFIRM_NOT_MATCH(1013, "Password confirm not match", HttpStatus.BAD_REQUEST),
    PASSWORD_SAME_AS_OLD(1014, "New password must be different from old password", HttpStatus.BAD_REQUEST),
    INVALID_TOKEN(1015, "Invalid or expired token", HttpStatus.BAD_REQUEST),
    EMAIL_ALREADY_EXISTED(1016, "This email is already registered!", HttpStatus.BAD_REQUEST),
    PHONE_ALREADY_EXISTS(1017, "Phone number already exists!", HttpStatus.BAD_REQUEST),
    ROLE_NOT_FOUND(1018, "Role has not been seeded!", HttpStatus.NOT_FOUND),
    OTP_INVALID(1019, "Invalid or expired OTP code", HttpStatus.BAD_REQUEST),
    EMAIL_ALREADY_VERIFIED(1020, "This account has already been verified", HttpStatus.BAD_REQUEST),
    OTP_RESEND_TOO_SOON(1021, "Please wait a while before requesting a new OTP", HttpStatus.TOO_MANY_REQUESTS),
    MAIL_SEND_FAILED(1022, "Failed to send verification email", HttpStatus.INTERNAL_SERVER_ERROR),
    ACCOUNT_TEMPORARILY_LOCKED(1023, "Account temporarily locked due to too many failed login attempts. Please try again in %d seconds.", HttpStatus.TOO_MANY_REQUESTS),
    USERNAME_EXISTED(1024, "Username existed", HttpStatus.BAD_REQUEST),

    // ---- Các key validation đang được dùng trong DTO của user/auth (trước đây thiếu -> valueOf() ném lỗi 500) ----
    EMAIL_INVALID(1025, "Email is invalid", HttpStatus.BAD_REQUEST),
    PHONE_INVALID(1026, "Phone number is invalid", HttpStatus.BAD_REQUEST),
    FIELD_REQUIRED(1027, "This field is required", HttpStatus.BAD_REQUEST),
    TOKEN_REQUIRED(1028, "Token is required", HttpStatus.BAD_REQUEST),

    // ---- Lỗi chung của tầng web ----
    INVALID_REQUEST(1030, "Invalid request", HttpStatus.BAD_REQUEST),
    RESOURCE_NOT_FOUND(1031, "Resource not found", HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(1032, "HTTP method not allowed", HttpStatus.METHOD_NOT_ALLOWED),

    // Catalog module
    BRAND_NOT_FOUND(2001, "Brand not found", HttpStatus.NOT_FOUND),
    BRAND_SLUG_EXISTED(2002, "Brand slug already exists", HttpStatus.BAD_REQUEST),
    BRAND_NAME_INVALID(2003, "Brand name must not be blank", HttpStatus.BAD_REQUEST),
    BRAND_SLUG_INVALID(2004, "Brand slug must not be blank", HttpStatus.BAD_REQUEST),
    BRAND_NAME_EXISTED(2005, "Brand name already exists", HttpStatus.BAD_REQUEST),
    BRAND_HAS_PRODUCTS(2006, "Cannot delete brand because it still has associated products", HttpStatus.BAD_REQUEST),

    CATEGORY_NOT_FOUND(2010, "Category not found", HttpStatus.NOT_FOUND),
    CATEGORY_SLUG_EXISTED(2011, "Category slug already exists", HttpStatus.BAD_REQUEST),
    CATEGORY_PARENT_NOT_FOUND(2012, "Parent category not found", HttpStatus.NOT_FOUND),
    CATEGORY_SELF_PARENT(2013, "A category cannot be its own parent", HttpStatus.BAD_REQUEST),
    CATEGORY_HAS_CHILDREN(2014, "Cannot delete a category that still has sub-categories", HttpStatus.BAD_REQUEST),
    CATEGORY_NAME_INVALID(2015, "Category name must not be blank", HttpStatus.BAD_REQUEST),
    CATEGORY_SLUG_INVALID(2016, "Category slug must not be blank", HttpStatus.BAD_REQUEST),
    CATEGORY_DISPLAY_ORDER_INVALID(2017, "Category display order must not be negative", HttpStatus.BAD_REQUEST),

    PRODUCT_NOT_FOUND(2020, "Product not found", HttpStatus.NOT_FOUND),
    PRODUCT_SLUG_EXISTED(2021, "Product slug already exists", HttpStatus.BAD_REQUEST),
    PRODUCT_NAME_INVALID(2022, "Product name must not be blank", HttpStatus.BAD_REQUEST),
    PRODUCT_SLUG_INVALID(2023, "Product slug must not be blank", HttpStatus.BAD_REQUEST),
    PRODUCT_PRICE_INVALID(2024, "Product price must be greater than 0", HttpStatus.BAD_REQUEST),
    PRODUCT_TYPE_INVALID(2025, "Product type must not be null", HttpStatus.BAD_REQUEST),
    PRODUCT_CATEGORY_INVALID(2026, "categoryId must not be null", HttpStatus.BAD_REQUEST),
    PRODUCT_BRAND_INVALID(2027, "brandId must not be null", HttpStatus.BAD_REQUEST),
    PRODUCT_STATUS_INVALID(2028, "Product status must not be null", HttpStatus.BAD_REQUEST),
    PRODUCT_DESCRIPTION_INVALID(2029, "Product description must not be blank", HttpStatus.BAD_REQUEST),

    PRODUCT_COLOR_NOT_FOUND(2030, "Product color not found", HttpStatus.NOT_FOUND),
    PRODUCT_COLOR_NAME_INVALID(2031, "Color name must not be blank", HttpStatus.BAD_REQUEST),
    PRODUCT_COLOR_HEX_INVALID(2032, "Color hex code must look like #RGB or #RRGGBB", HttpStatus.BAD_REQUEST),
    PRODUCT_COLOR_NAME_EXISTED(2033, "This product already has a color with the same name", HttpStatus.BAD_REQUEST),
    PRODUCT_COLOR_HAS_VARIANTS(2034, "Cannot delete a color that still has variants. Deactivate or move its variants first", HttpStatus.CONFLICT),

    PRODUCT_VARIANT_NOT_FOUND(2040, "Product variant not found", HttpStatus.NOT_FOUND),
    PRODUCT_VARIANT_SKU_EXISTED(2041, "SKU already exists", HttpStatus.BAD_REQUEST),
    PRODUCT_VARIANT_SKU_INVALID(2042, "SKU must not be blank", HttpStatus.BAD_REQUEST),
    PRODUCT_VARIANT_STOCK_INVALID(2043, "Stock must not be negative", HttpStatus.BAD_REQUEST),
    PRODUCT_VARIANT_INSUFFICIENT_STOCK(2044, "Insufficient stock", HttpStatus.CONFLICT),
    PRODUCT_VARIANT_INACTIVE(2045, "This variant is not available for sale", HttpStatus.CONFLICT),
    PRODUCT_VARIANT_QUANTITY_INVALID(2046, "Quantity must be greater than 0", HttpStatus.BAD_REQUEST),

    PRODUCT_IMAGE_NOT_FOUND(2050, "Product image not found", HttpStatus.NOT_FOUND),
    PRODUCT_IMAGE_URL_INVALID(2051, "Image url must not be blank", HttpStatus.BAD_REQUEST),

    PRODUCT_ATTRIBUTE_NOT_FOUND(2060, "Product attribute not found", HttpStatus.NOT_FOUND),
    PRODUCT_ATTR_NAME_INVALID(2061, "Attribute name must not be blank", HttpStatus.BAD_REQUEST),
    PRODUCT_ATTR_VALUE_INVALID(2062, "Attribute value must not be blank", HttpStatus.BAD_REQUEST),

    PRODUCT_SALE_PRICE_INVALID(2070, "Sale price must be greater than 0 and lower than the regular price", HttpStatus.BAD_REQUEST),

    // File module
    FILE_EMPTY(2100, "Uploaded file must not be empty", HttpStatus.BAD_REQUEST),
    FILE_TYPE_NOT_SUPPORTED(2101, "Only image files (jpg, jpeg, png, webp) are supported", HttpStatus.BAD_REQUEST),
    FILE_TOO_LARGE(2102, "File size must not exceed 5MB", HttpStatus.BAD_REQUEST),
    FILE_UPLOAD_FAILED(2103, "Failed to upload file to Cloudinary", HttpStatus.INTERNAL_SERVER_ERROR),
    FILE_DELETE_FAILED(2104, "Failed to delete file on Cloudinary", HttpStatus.INTERNAL_SERVER_ERROR),

    // Cart module
    CART_ITEM_NOT_FOUND(2200, "Cart item not found", HttpStatus.NOT_FOUND),
    CART_EMPTY(2201, "Cart is empty", HttpStatus.BAD_REQUEST),
    CART_MERGE_EMPTY(2202, "No items to merge", HttpStatus.BAD_REQUEST),
    CART_QUANTITY_EXCEEDED(2203, "Quantity exceeds available stock or maximum allowed", HttpStatus.BAD_REQUEST),
    CART_TOKEN_REQUIRED(2204, "Cart token is required for guest cart operations", HttpStatus.BAD_REQUEST),

    // [MỚI] Order module (23xx)
    ORDER_NOT_FOUND(2300, "Order not found", HttpStatus.NOT_FOUND),
    ORDER_CANNOT_BE_CANCELLED(2301, "Order cannot be cancelled in current status", HttpStatus.BAD_REQUEST),
    ORDER_STATUS_INVALID_TRANSITION(2302, "Invalid order status transition", HttpStatus.BAD_REQUEST),
    ORDER_ACCESS_DENIED(2303, "You do not have permission to access this order", HttpStatus.FORBIDDEN),
    ORDER_PAYMENT_METHOD_INVALID(2304, "Invalid payment method", HttpStatus.BAD_REQUEST),
    ORDER_SHIPPING_METHOD_INVALID(2305, "Invalid shipping method", HttpStatus.BAD_REQUEST),
    ORDER_ALREADY_PAID(2306, "Order has already been paid", HttpStatus.BAD_REQUEST),
    ORDER_ITEMS_EMPTY(2307, "Order must contain at least one item", HttpStatus.BAD_REQUEST),
    ORDER_EXPIRED(2308, "Order has expired", HttpStatus.BAD_REQUEST),

    // [MỚI] Payment module (24xx)
    PAYMENT_TRANSACTION_NOT_FOUND(2400, "Payment transaction not found", HttpStatus.NOT_FOUND),
    PAYMENT_INVALID_AMOUNT(2401, "Invalid payment amount", HttpStatus.BAD_REQUEST),
    PAYMENT_INVALID_SIGNATURE(2402, "Invalid payment signature", HttpStatus.BAD_REQUEST),
    PAYMENT_TRANSACTION_ALREADY_PROCESSED(2403, "Payment transaction already processed", HttpStatus.BAD_REQUEST),
    PAYMENT_CREATION_FAILED(2404, "Failed to create payment URL", HttpStatus.INTERNAL_SERVER_ERROR),
    PAYMENT_QUERYDR_FAILED(2405, "Failed to query transaction status from VNPay", HttpStatus.INTERNAL_SERVER_ERROR),
    PAYMENT_REFUND_FAILED(2406, "Failed to refund transaction via VNPay", HttpStatus.INTERNAL_SERVER_ERROR),

    // [MỚI] Shipping module (25xx)
    SHIPPING_SERVICE_NOT_AVAILABLE(2500, "Shipping service not available for this route", HttpStatus.BAD_REQUEST),
    SHIPPING_FEE_CALCULATION_FAILED(2501, "Failed to calculate shipping fee", HttpStatus.INTERNAL_SERVER_ERROR),
    SHIPPING_ORDER_CREATION_FAILED(2502, "Failed to create shipping order with GHN", HttpStatus.INTERNAL_SERVER_ERROR),
    SHIPPING_ORDER_CANCEL_FAILED(2503, "Failed to cancel shipping order with GHN", HttpStatus.INTERNAL_SERVER_ERROR),
    SHIPMENT_NOT_FOUND(2504, "Shipment record not found", HttpStatus.NOT_FOUND),
    SHIPPING_WEBHOOK_UNAUTHORIZED(2505, "Unauthorized shipping webhook request", HttpStatus.UNAUTHORIZED),
    SHIPPING_TRACKING_FAILED(2506, "Failed to fetch shipping tracking detail", HttpStatus.INTERNAL_SERVER_ERROR),

    // [MỚI] Location module (26xx)
    PROVINCE_NOT_FOUND(2600, "Province not found", HttpStatus.NOT_FOUND),
    DISTRICT_NOT_FOUND(2601, "District not found", HttpStatus.NOT_FOUND),
    WARD_NOT_FOUND(2602, "Ward not found", HttpStatus.NOT_FOUND),
    ADDRESS_NOT_FOUND(2603, "Address not found", HttpStatus.NOT_FOUND),
    ADDRESS_ACCESS_DENIED(2604, "You do not have permission to access this address", HttpStatus.FORBIDDEN),
    ADDRESS_LOCATION_MISMATCH(2605, "District does not belong to province or ward does not belong to district", HttpStatus.BAD_REQUEST),
    LOCATION_SYNC_FAILED(2606, "Failed to sync locations from GHN", HttpStatus.INTERNAL_SERVER_ERROR),
    ;

    private int code = 1000;
    private String message;
    private HttpStatusCode statusCode;

    ErrorCode(int code, String message, HttpStatusCode statusCode) {
        this.code = code;
        this.message = message;
        this.statusCode = statusCode;
    }
}