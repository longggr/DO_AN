package org.example.smashhub.order.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.example.smashhub.common.response.ApiResponse;
import org.example.smashhub.order.dto.request.CheckoutRequest;
import org.example.smashhub.order.dto.response.OrderResponse;
import org.example.smashhub.order.service.OrderService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class OrderController {

    OrderService orderService;

    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : null;
    }

    private String getClientIp(HttpServletRequest request) {
        String xf = request.getHeader("X-Forwarded-For");
        if (xf != null && !xf.isBlank()) {
            return xf.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    @PostMapping("/checkout")
    public ResponseEntity<ApiResponse<OrderResponse>> checkout(
            @RequestBody @Valid CheckoutRequest request,
            HttpServletRequest servletRequest) {
        OrderResponse result = orderService.checkout(currentUsername(), request, getClientIp(servletRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.<OrderResponse>builder()
                .result(result)
                .message("Đặt hàng thành công")
                .build());
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Page<OrderResponse>>> getMyOrders(
            @PageableDefault(size = 10, sort = "createDate", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.<Page<OrderResponse>>builder()
                .result(orderService.getMyOrders(currentUsername(), pageable))
                .message("Lấy danh sách đơn hàng thành công")
                .build());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrderById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.<OrderResponse>builder()
                .result(orderService.getOrderById(currentUsername(), id))
                .message("Lấy chi tiết đơn hàng thành công")
                .build());
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<ApiResponse<OrderResponse>> cancelOrder(
            @PathVariable Long id,
            @RequestParam(required = false, defaultValue = "Khách hàng yêu cầu hủy đơn") String reason) {
        return ResponseEntity.ok(ApiResponse.<OrderResponse>builder()
                .result(orderService.cancelOrder(currentUsername(), id, reason))
                .message("Hủy đơn hàng thành công")
                .build());
    }

    @PostMapping("/{id}/repay")
    public ResponseEntity<ApiResponse<OrderResponse>> repay(
            @PathVariable Long id,
            HttpServletRequest servletRequest) {
        return ResponseEntity.ok(ApiResponse.<OrderResponse>builder()
                .result(orderService.repay(currentUsername(), id, getClientIp(servletRequest)))
                .message("Tạo đường dẫn thanh toán mới thành công")
                .build());
    }
}

