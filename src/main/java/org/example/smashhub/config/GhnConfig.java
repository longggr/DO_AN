package org.example.smashhub.config;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldDefaults;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * [MỚI] Cấu hình tích hợp Giao Hàng Nhanh (GHN) API.
 * Đọc từ prefix 'ghn' trong application.yaml hoặc biến môi trường.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "ghn")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class GhnConfig {

    String baseUrl;
    String token;
    Integer shopId;
    Integer fromDistrictId;
    String fromWardCode;
    Integer defaultWeightGram = 500;
    Integer defaultLengthCm = 30;
    Integer defaultWidthCm = 20;
    Integer defaultHeightCm = 10;
    String webhookToken;
    String syncCron;
}

