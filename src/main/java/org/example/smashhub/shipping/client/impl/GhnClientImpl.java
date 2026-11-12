package org.example.smashhub.shipping.client.impl;

import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.example.smashhub.config.GhnConfig;
import org.example.smashhub.exception.AppException;
import org.example.smashhub.exception.ErrorCode;
import org.example.smashhub.shipping.client.GhnClient;
import org.example.smashhub.shipping.dto.GhnCreateOrderRequest;
import org.example.smashhub.shipping.dto.GhnCreateOrderResponse;
import org.example.smashhub.shipping.dto.GhnFeeCalculationRequest;
import org.example.smashhub.shipping.dto.GhnServiceInfo;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

@Component
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class GhnClientImpl implements GhnClient {

    GhnConfig ghnConfig;
    RestClient restClient;
    ObjectMapper objectMapper = new ObjectMapper();

    public GhnClientImpl(GhnConfig ghnConfig) {
        this.ghnConfig = ghnConfig;
        validateGhnConfig();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(15));

        this.restClient = RestClient.builder()
                .baseUrl(ghnConfig.getBaseUrl())
                .requestFactory(factory)
                .defaultHeader("Token", ghnConfig.getToken())
                .defaultHeader("ShopId", String.valueOf(ghnConfig.getShopId()))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    private void validateGhnConfig() {
        String baseUrl = ghnConfig.getBaseUrl();
        String token = ghnConfig.getToken();
        Integer shopId = ghnConfig.getShopId();

        if (baseUrl == null || baseUrl.isBlank() || !baseUrl.startsWith("http")) {
            throw new AppException(ErrorCode.SHIPPING_SERVICE_NOT_AVAILABLE,
                    "Cấu hình GHN chưa hợp lệ: baseUrl không được để trống.");
        }

        if (token == null || token.isBlank()) {
            throw new AppException(ErrorCode.SHIPPING_SERVICE_NOT_AVAILABLE,
                    "Cấu hình GHN chưa hợp lệ: GHN token đang trống.");
        }

        String normalizedToken = token.trim().toLowerCase(Locale.ROOT);
        if (normalizedToken.contains("sample_ghn_token") || normalizedToken.contains("your_ghn_sandbox_token") || normalizedToken.contains("your_ghn") || normalizedToken.contains("example")) {
            throw new AppException(ErrorCode.SHIPPING_SERVICE_NOT_AVAILABLE,
                    "Cấu hình GHN chưa hợp lệ: GHN token vẫn là giá trị mẫu.");
        }

        if (shopId == null || shopId <= 0) {
            throw new AppException(ErrorCode.SHIPPING_SERVICE_NOT_AVAILABLE,
                    "Cấu hình GHN chưa hợp lệ: ShopId đang trống.");
        }

        if (shopId == 123456) {
            throw new AppException(ErrorCode.SHIPPING_SERVICE_NOT_AVAILABLE,
                    "Cấu hình GHN chưa hợp lệ: ShopId đang là giá trị mẫu.");
        }
    }

    @Override
    public List<GhnServiceInfo> getAvailableServices(int fromDistrict, int toDistrict) {
        return executeWithRetry(() -> {
            Map<String, Object> body = Map.of(
                    "shop_id", ghnConfig.getShopId(),
                    "from_district", fromDistrict,
                    "to_district", toDistrict
            );

            String response = restClient.post()
                    .uri("/v2/shipping-order/available-services")
                    .body(body)
                    .retrieve()
                    .body(String.class);

            List<GhnServiceInfo> services = new ArrayList<>();
            try {
                JsonNode root = objectMapper.readTree(response);
                JsonNode data = root.get("data");
                if (data != null && data.isArray()) {
                    for (JsonNode s : data) {
                        services.add(GhnServiceInfo.builder()
                                .serviceId(s.get("service_id").asInt())
                                .shortName(s.has("short_name") ? s.get("short_name").asText() : "")
                                .serviceTypeId(s.has("service_type_id") ? s.get("service_type_id").asInt() : 2)
                                .build());
                    }
                }
            } catch (Exception e) {
                log.error("Lỗi parse available-services từ GHN: ", e);
                throw new AppException(ErrorCode.SHIPPING_SERVICE_NOT_AVAILABLE, "Không thể đọc danh sách dịch vụ GHN");
            }
            return services;
        });
    }

    @Override
    public BigDecimal calculateFee(GhnFeeCalculationRequest request) {
        return executeWithRetry(() -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("from_district_id", request.getFromDistrictId());
            if (request.getFromWardCode() != null) body.put("from_ward_code", request.getFromWardCode());
            body.put("to_district_id", request.getToDistrictId());
            body.put("to_ward_code", request.getToWardCode());
            if (request.getServiceId() != null) body.put("service_id", request.getServiceId());
            if (request.getServiceTypeId() != null) body.put("service_type_id", request.getServiceTypeId());
            body.put("weight", request.getWeight() != null ? request.getWeight() : ghnConfig.getDefaultWeightGram());
            body.put("length", request.getLength() != null ? request.getLength() : ghnConfig.getDefaultLengthCm());
            body.put("width", request.getWidth() != null ? request.getWidth() : ghnConfig.getDefaultWidthCm());
            body.put("height", request.getHeight() != null ? request.getHeight() : ghnConfig.getDefaultHeightCm());
            if (request.getInsuranceValue() != null && request.getInsuranceValue().compareTo(BigDecimal.ZERO) > 0) {
                body.put("insurance_value", request.getInsuranceValue().intValue());
            }

            String response = restClient.post()
                    .uri("/v2/shipping-order/fee")
                    .body(body)
                    .retrieve()
                    .body(String.class);

            try {
                JsonNode root = objectMapper.readTree(response);
                JsonNode data = root.get("data");
                if (data != null && data.has("total")) {
                    return BigDecimal.valueOf(data.get("total").asDouble());
                }
                throw new AppException(ErrorCode.SHIPPING_FEE_CALCULATION_FAILED, "Dữ liệu cước GHN trả về không hợp lệ");
            } catch (AppException e) {
                throw e;
            } catch (Exception e) {
                log.error("Lỗi parse fee từ GHN: ", e);
                throw new AppException(ErrorCode.SHIPPING_FEE_CALCULATION_FAILED, e.getMessage());
            }
        });
    }

    @Override
    public LocalDateTime getLeadtime(int fromDistrict, String fromWard, int toDistrict, String toWard, int serviceId) {
        return executeWithRetry(() -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("from_district_id", fromDistrict);
            body.put("from_ward_code", fromWard);
            body.put("to_district_id", toDistrict);
            body.put("to_ward_code", toWard);
            body.put("service_id", serviceId);

            String response = restClient.post()
                    .uri("/v2/shipping-order/leadtime")
                    .body(body)
                    .retrieve()
                    .body(String.class);

            try {
                JsonNode root = objectMapper.readTree(response);
                JsonNode data = root.get("data");
                if (data != null && data.has("leadtime")) {
                    long epoch = data.get("leadtime").asLong();
                    return LocalDateTime.ofInstant(Instant.ofEpochSecond(epoch), ZoneId.of("Asia/Ho_Chi_Minh"));
                }
            } catch (Exception e) {
                log.warn("Không lấy được leadtime từ GHN: {}", e.getMessage());
            }
            // Mặc định sau 3 ngày nếu không lấy được leadtime
            return LocalDateTime.now().plusDays(3);
        });
    }

    @Override
    public GhnCreateOrderResponse createOrder(GhnCreateOrderRequest request) {
        return executeWithRetry(() -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("payment_type_id", 1); // 1: Shop trả cước (vì Shop đã thu phí vận chuyển của khách tại checkout)
            body.put("note", request.getNote() != null ? request.getNote() : "SmashHub Order");
            body.put("required_note", "CHOXEMHANGKHONGTHU");
            body.put("client_order_code", request.getClientOrderCode());

            body.put("to_name", request.getToName());
            body.put("to_phone", request.getToPhone());
            body.put("to_address", request.getToAddress());
            body.put("to_district_id", request.getToDistrictId());
            body.put("to_ward_code", request.getToWardCode());

            body.put("weight", request.getWeight() != null ? request.getWeight() : ghnConfig.getDefaultWeightGram());
            body.put("length", request.getLength() != null ? request.getLength() : ghnConfig.getDefaultLengthCm());
            body.put("width", request.getWidth() != null ? request.getWidth() : ghnConfig.getDefaultWidthCm());
            body.put("height", request.getHeight() != null ? request.getHeight() : ghnConfig.getDefaultHeightCm());

            if (request.getServiceId() != null) body.put("service_id", request.getServiceId());
            if (request.getServiceTypeId() != null) body.put("service_type_id", request.getServiceTypeId());

            // COD amount: Đơn COD thì thu tổng tiền; Đơn VNPay đã trả thì thu 0đ
            int cod = request.getCodAmount() != null ? request.getCodAmount().intValue() : 0;
            body.put("cod_amount", cod);

            if (request.getInsuranceValue() != null) {
                body.put("insurance_value", request.getInsuranceValue().intValue());
            }

            if (request.getItems() != null && !request.getItems().isEmpty()) {
                body.put("items", request.getItems());
            }

            String response = restClient.post()
                    .uri("/v2/shipping-order/create")
                    .body(body)
                    .retrieve()
                    .body(String.class);

            try {
                JsonNode root = objectMapper.readTree(response);
                JsonNode data = root.get("data");
                if (data != null && data.has("order_code")) {
                    String orderCode = data.get("order_code").asText();
                    BigDecimal totalFee = data.has("total_fee") ? BigDecimal.valueOf(data.get("total_fee").asDouble()) : BigDecimal.ZERO;
                    LocalDateTime expected = null;
                    if (data.has("expected_delivery_time")) {
                        try {
                            String expStr = data.get("expected_delivery_time").asText();
                            expected = LocalDateTime.parse(expStr.substring(0, 19));
                        } catch (Exception ignored) {
                            expected = LocalDateTime.now().plusDays(3);
                        }
                    }

                    return GhnCreateOrderResponse.builder()
                            .orderCode(orderCode)
                            .totalFee(totalFee)
                            .expectedDeliveryDate(expected)
                            .rawResponse(response)
                            .build();
                }
                throw new AppException(ErrorCode.SHIPPING_ORDER_CREATION_FAILED, "GHN không trả về mã vận đơn");
            } catch (AppException e) {
                throw e;
            } catch (Exception e) {
                log.error("Lỗi parse kết quả tạo đơn GHN: ", e);
                throw new AppException(ErrorCode.SHIPPING_ORDER_CREATION_FAILED, e.getMessage());
            }
        });
    }

    @Override
    public void cancelOrder(String orderCode) {
        executeWithRetry(() -> {
            Map<String, Object> body = Map.of("order_codes", List.of(orderCode));
            try {
                restClient.post()
                        .uri("/v2/switch-status/cancel")
                        .body(body)
                        .retrieve()
                        .toBodilessEntity();
            } catch (Exception e) {
                log.error("Lỗi khi hủy đơn GHN {}: {}", orderCode, e.getMessage());
                throw new AppException(ErrorCode.SHIPPING_ORDER_CANCEL_FAILED, e.getMessage());
            }
            return null;
        });
    }

    @Override
    public String getPrintToken(List<String> orderCodes) {
        return executeWithRetry(() -> {
            Map<String, Object> body = Map.of("order_codes", orderCodes);
            String response = restClient.post()
                    .uri("/v2/a5/gen-token")
                    .body(body)
                    .retrieve()
                    .body(String.class);

            try {
                JsonNode root = objectMapper.readTree(response);
                JsonNode data = root.get("data");
                if (data != null && data.has("token")) {
                    return data.get("token").asText();
                }
            } catch (Exception e) {
                log.warn("Lỗi lấy print token GHN: {}", e.getMessage());
            }
            return null;
        });
    }

    @Override
    public JsonNode getOrderDetail(String orderCode) {
        return executeWithRetry(() -> {
            Map<String, Object> body = Map.of("order_code", orderCode);
            String response = restClient.post()
                    .uri("/v2/shipping-order/detail")
                    .body(body)
                    .retrieve()
                    .body(String.class);

            try {
                JsonNode root = objectMapper.readTree(response);
                return root.get("data");
            } catch (Exception e) {
                log.error("Lỗi đọc chi tiết vận đơn GHN {}: ", orderCode, e);
                throw new AppException(ErrorCode.SHIPPING_TRACKING_FAILED, e.getMessage());
            }
        });
    }

    // ---------------------- Retry Logic Helper ----------------------

    private <T> T executeWithRetry(Supplier<T> action) {
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (HttpClientErrorException e) {
                // 4xx: lỗi nghiệp vụ từ client -> KHÔNG retry
                log.error("GHN API 4xx error (attempt {}): status={}, body={}", attempt, e.getStatusCode(), e.getResponseBodyAsString());
                throw new AppException(ErrorCode.SHIPPING_FEE_CALCULATION_FAILED, "Lỗi từ dịch vụ vận chuyển: " + e.getResponseBodyAsString());
            } catch (HttpServerErrorException | ResourceAccessException e) {
                // 5xx hoặc Timeout/Connection error -> retry
                log.warn("GHN API call failure (attempt {}/{}): {}", attempt, maxAttempts, e.getMessage());
                if (attempt == maxAttempts) {
                    throw new AppException(ErrorCode.SHIPPING_FEE_CALCULATION_FAILED, "Dịch vụ vận chuyển GHN tạm thời không phản hồi. Vui lòng thử lại sau.");
                }
                try {
                    Thread.sleep(attempt * 500L); // backoff
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ie);
                }
            }
        }
        throw new AppException(ErrorCode.SHIPPING_FEE_CALCULATION_FAILED, "Không thể kết nối tới GHN");
    }
}

