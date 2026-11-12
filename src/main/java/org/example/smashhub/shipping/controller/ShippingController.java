package org.example.smashhub.shipping.controller;

import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.example.smashhub.common.response.ApiResponse;
import org.example.smashhub.shipping.dto.GhnWebhookPayload;
import org.example.smashhub.shipping.dto.ShipmentTrackingResponse;
import org.example.smashhub.shipping.dto.ShippingFeeRequest;
import org.example.smashhub.shipping.dto.ShippingFeeResponse;
import org.example.smashhub.shipping.service.ShippingService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ShippingController {

    ShippingService shippingService;

    private String currentUsernameOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName()))
                ? auth.getName() : null;
    }

    @PostMapping("/shipping/fee")
    public ResponseEntity<ApiResponse<ShippingFeeResponse>> calculateFee(
            @RequestBody @Valid ShippingFeeRequest request) {
        return ResponseEntity.ok(ApiResponse.<ShippingFeeResponse>builder()
                .result(shippingService.calculateFee(currentUsernameOrNull(), request))
                .message("Tính phí vận chuyển thành công")
                .build());
    }

    @PostMapping("/shipping/ghn/webhook")
    public ResponseEntity<ApiResponse<String>> handleGhnWebhook(
            @RequestHeader(value = "Token", required = false) String token,
            @RequestBody GhnWebhookPayload payload) {
        shippingService.handleWebhook(token, payload);
        return ResponseEntity.ok(ApiResponse.<String>builder()
                .result("OK")
                .message("Webhook processed successfully")
                .build());
    }

    @GetMapping("/orders/{orderId}/tracking")
    public ResponseEntity<ApiResponse<ShipmentTrackingResponse>> getTrackingInfo(
            @PathVariable Long orderId) {
        return ResponseEntity.ok(ApiResponse.<ShipmentTrackingResponse>builder()
                .result(shippingService.getTrackingInfo(orderId, currentUsernameOrNull()))
                .message("Lấy thông tin hành trình đơn hàng thành công")
                .build());
    }
}

