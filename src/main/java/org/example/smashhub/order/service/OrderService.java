package org.example.smashhub.order.service;

import org.example.smashhub.common.enums.OrderStatus;
import org.example.smashhub.order.dto.request.CheckoutRequest;
import org.example.smashhub.order.dto.request.OrderStatusUpdateRequest;
import org.example.smashhub.order.dto.response.OrderResponse;
import org.example.smashhub.shipping.entity.Shipment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface OrderService {

    OrderResponse checkout(String username, CheckoutRequest request, String ipAddress);

    Page<OrderResponse> getMyOrders(String username, Pageable pageable);

    OrderResponse getOrderById(String username, Long orderId);

    OrderResponse cancelOrder(String username, Long orderId, String reason);

    OrderResponse repay(String username, Long orderId, String ipAddress);

    Page<OrderResponse> getOrdersForAdmin(OrderStatus status, Pageable pageable);

    OrderResponse getOrderDetailForAdmin(Long orderId);

    OrderResponse updateOrderStatusAdmin(Long orderId, OrderStatusUpdateRequest request, String staffUsername);

    Shipment retryShipmentAdmin(Long orderId);
}

