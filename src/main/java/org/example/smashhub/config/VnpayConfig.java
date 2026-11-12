package org.example.smashhub.config;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldDefaults;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * [MỚI] Cấu hình tích hợp Cổng thanh toán VNPay.
 * Đọc từ prefix 'vnpay' trong application.yaml hoặc biến môi trường.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "vnpay")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class VnpayConfig {

    String tmnCode;
    String hashSecret;
    String payUrl;
    String apiUrl;
    String returnUrl;
    Integer expireMinutes = 15;
}

