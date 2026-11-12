package org.example.smashhub.payment.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.example.smashhub.common.response.ApiResponse;
import org.example.smashhub.payment.dto.VnpayIpnResponse;
import org.example.smashhub.payment.dto.VnpayReturnResponse;
import org.example.smashhub.payment.service.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/payment/vnpay")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class PaymentController {

    PaymentService paymentService;

    /**
     * IPN URL nhận callback ngầm từ VNPay (Server-to-Server).
     * Bắt buộc luôn trả về HTTP 200 kèm JSON chuẩn định dạng: {"RspCode":"00","Message":"Confirm Success"}.
     */
    @GetMapping("/ipn")
    public ResponseEntity<VnpayIpnResponse> handleIpnGet(@RequestParam Map<String, String> params) {
        VnpayIpnResponse response = paymentService.processIpn(params);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/ipn")
    public ResponseEntity<VnpayIpnResponse> handleIpnPost(@RequestParam Map<String, String> params) {
        VnpayIpnResponse response = paymentService.processIpn(params);
        return ResponseEntity.ok(response);
    }

    /**
     * Return URL sau khi người dùng thực hiện thanh toán xong trên cổng VNPay.
     * TUYỆT ĐỐI không cập nhật trạng thái đơn hàng ở đây, chỉ hiển thị kết quả cho khách.
     */
    @GetMapping("/return")
    public ResponseEntity<ApiResponse<VnpayReturnResponse>> handleReturn(
            @RequestParam Map<String, String> params) {
        VnpayReturnResponse response = paymentService.processReturn(params);
        return ResponseEntity.ok(ApiResponse.<VnpayReturnResponse>builder()
                .result(response)
                .message("Kết quả thanh toán từ VNPay")
                .build());
    }
}

