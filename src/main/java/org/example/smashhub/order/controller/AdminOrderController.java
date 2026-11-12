package org.example.smashhub.order.controller;

import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.example.smashhub.common.enums.OrderStatus;
import org.example.smashhub.common.response.ApiResponse;
import org.example.smashhub.order.dto.request.OrderStatusUpdateRequest;
import org.example.smashhub.order.dto.response.OrderResponse;
import org.example.smashhub.order.service.OrderService;
import org.example.smashhub.shipping.entity.Shipment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/orders")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','STAFF')")
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AdminOrderController {

    OrderService orderService;

    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "STAFF";
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Page<OrderResponse>>> getOrders(
            @RequestParam(required = false) OrderStatus status,
            @PageableDefault(size = 15, sort = "createDate", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.<Page<OrderResponse>>builder()
                .result(orderService.getOrdersForAdmin(status, pageable))
                .message("Lấy danh sách đơn hàng cho Quản trị thành công")
                .build());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrderDetail(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.<OrderResponse>builder()
                .result(orderService.getOrderDetailForAdmin(id))
                .message("Lấy thông tin chi tiết đơn hàng thành công")
                .build());
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<ApiResponse<OrderResponse>> updateStatus(
            @PathVariable Long id,
            @RequestBody @Valid OrderStatusUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.<OrderResponse>builder()
                .result(orderService.updateOrderStatusAdmin(id, request, currentUsername()))
                .message("Cập nhật trạng thái đơn hàng thành công")
                .build());
    }

    @PostMapping("/{id}/retry-shipment")
    public ResponseEntity<ApiResponse<Shipment>> retryShipment(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.<Shipment>builder()
                .result(orderService.retryShipmentAdmin(id))
                .message("Thử lại tạo vận đơn GHN thành công")
                .build());
    }
}

