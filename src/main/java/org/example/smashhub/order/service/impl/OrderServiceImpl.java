package org.example.smashhub.order.service.impl;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.example.smashhub.cart.entity.CartItem;
import org.example.smashhub.cart.repository.CartItemRepository;
import org.example.smashhub.catalog.entity.Product;
import org.example.smashhub.catalog.entity.ProductVariant;
import org.example.smashhub.catalog.service.InventoryService;
import org.example.smashhub.catalog.util.PriceCalculator;
import org.example.smashhub.common.enums.OrderStatus;
import org.example.smashhub.common.enums.PaymentMethod;
import org.example.smashhub.common.enums.ProductStatus;
import org.example.smashhub.common.enums.VariantStatus;
import org.example.smashhub.exception.AppException;
import org.example.smashhub.exception.ErrorCode;
import org.example.smashhub.location.entity.Address;
import org.example.smashhub.location.repository.AddressRepository;
import org.example.smashhub.order.dto.request.CheckoutRequest;
import org.example.smashhub.order.dto.request.OrderStatusUpdateRequest;
import org.example.smashhub.order.dto.response.OrderResponse;
import org.example.smashhub.order.entity.Order;
import org.example.smashhub.order.entity.OrderItem;
import org.example.smashhub.order.entity.OrderStatusHistory;
import org.example.smashhub.order.mapper.OrderMapper;
import org.example.smashhub.order.repository.OrderItemRepository;
import org.example.smashhub.order.repository.OrderRepository;
import org.example.smashhub.order.repository.OrderStatusHistoryRepository;
import org.example.smashhub.order.service.OrderService;
import org.example.smashhub.order.service.OrderStateMachine;
import org.example.smashhub.payment.service.PaymentService;
import org.example.smashhub.shipping.dto.ShippingFeeRequest;
import org.example.smashhub.shipping.dto.ShippingFeeResponse;
import org.example.smashhub.shipping.entity.Shipment;
import org.example.smashhub.shipping.service.ShippingService;
import org.example.smashhub.user.entity.User;
import org.example.smashhub.user.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class OrderServiceImpl implements OrderService {

    UserRepository userRepository;
    AddressRepository addressRepository;
    CartItemRepository cartItemRepository;
    OrderRepository orderRepository;
    OrderItemRepository orderItemRepository;
    OrderStatusHistoryRepository statusHistoryRepository;
    InventoryService inventoryService;
    ShippingService shippingService;
    PaymentService paymentService;
    OrderStateMachine orderStateMachine;
    OrderMapper orderMapper;
    StringRedisTemplate redisTemplate;

    @Override
    @Transactional
    public OrderResponse checkout(String username, CheckoutRequest request, String ipAddress) {
        log.info("User {} bắt đầu quy trình Checkout đơn hàng", username);

        // 1. Kiểm tra tài khoản người dùng
        User user = requireUser(username);

        // [SỬA] Khóa Redis theo User ID để chống double-click hoặc 2 tab checkout đồng thời tạo 2 đơn trùng lặp
        String checkoutLockKey = "lock:checkout:" + user.getId();
        if (redisTemplate != null) {
            Boolean lockAcquired = redisTemplate.opsForValue().setIfAbsent(checkoutLockKey, "LOCKED", Duration.ofSeconds(5));
            if (!Boolean.TRUE.equals(lockAcquired)) {
                throw new AppException(ErrorCode.ORDER_CANNOT_BE_CANCELLED,
                        "Yêu cầu đặt hàng đang được xử lý, vui lòng không nhấn liên tục!");
            }
        }

        try {
            // 2. Lấy giỏ hàng trong DB
            List<CartItem> cartItems = cartItemRepository.findByUserIdWithDetails(user.getId());
            if (cartItems == null || cartItems.isEmpty()) {
                throw new AppException(ErrorCode.CART_EMPTY);
            }

            // 3. Kiểm tra địa chỉ giao hàng thuộc về chính người dùng (Chặn IDOR)
            Address address = addressRepository.findByIdAndUserId(request.getAddressId(), user.getId())
                    .orElseThrow(() -> new AppException(ErrorCode.ADDRESS_NOT_FOUND));

            // 4. Tính lại giá từng mặt hàng từ DB bằng PriceCalculator và kiểm tra tính khả dụng
            BigDecimal subtotal = BigDecimal.ZERO;
            for (CartItem ci : cartItems) {
                ProductVariant variant = ci.getVariant();
                Product product = variant.getColor().getProduct();

                if (variant.getActive() != VariantStatus.ACTIVE || product.getProductStatus() != ProductStatus.ACTIVE) {
                    throw new AppException(ErrorCode.PRODUCT_VARIANT_INACTIVE,
                            "Sản phẩm " + product.getName() + " hiện không thể mua");
                }

                BigDecimal unitPrice = PriceCalculator.effectivePrice(product.getPrice(), product.getSalePrice());
                BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(ci.getQuantity()));
                subtotal = subtotal.add(lineTotal);
            }

            // 5. Gọi GHN để SERVER tự tính phí vận chuyển (Tuyệt đối không tin phí từ client)
            ShippingFeeRequest feeReq = ShippingFeeRequest.builder()
                    .addressId(address.getId())
                    .toDistrictId(address.getDistrict().getId())
                    .toWardCode(address.getWard().getCode())
                    .shippingMethod(request.getShippingMethod())
                    .build();

            ShippingFeeResponse feeRes = shippingService.calculateFee(username, feeReq);
            BigDecimal shippingCost = feeRes.getShippingFee();
            BigDecimal discount = BigDecimal.ZERO;
            BigDecimal total = subtotal.add(shippingCost).subtract(discount);

            // 6. Trừ tồn kho nguyên tử bằng InventoryService.deductStock cho từng dòng
            // Nếu có SKU nào không đủ hàng -> ném lỗi và tự động rollback toàn bộ transaction
            for (CartItem ci : cartItems) {
                inventoryService.deductStock(ci.getVariant().getId(), ci.getQuantity());
            }

            // 7. Tạo đối tượng Order
            OrderStatus initialStatus = (request.getPaymentMethod() == PaymentMethod.VNPAY)
                    ? OrderStatus.PENDING_PAYMENT
                    : OrderStatus.PROCESSING;

            Order order = Order.builder()
                    .user(user)
                    .orderStatus(initialStatus)
                    .shippingMethod(request.getShippingMethod())
                    .shippingRecipientName(address.getRecipientName())
                    .shippingPhone(address.getPhone() != null ? address.getPhone() : user.getPhone())
                    .shippingLine1(address.getLine1())
                    .shippingProvince(address.getProvince())
                    .shippingDistrict(address.getDistrict())
                    .shippingWard(address.getWard())
                    .subtotal(subtotal)
                    .shippingCost(shippingCost)
                    .discount(discount)
                    .total(total)
                    .paymentMethod(request.getPaymentMethod())
                    .note(request.getNote())
                    .stockRestored(false)
                    .build();

            Order savedOrder = orderRepository.save(order);

            // 8. Tạo OrderItem snapshot lưu lại dữ liệu lịch sử giá & sản phẩm
            List<OrderItem> orderItems = new ArrayList<>();
            for (CartItem ci : cartItems) {
                ProductVariant variant = ci.getVariant();
                Product product = variant.getColor().getProduct();
                BigDecimal unitPrice = PriceCalculator.effectivePrice(product.getPrice(), product.getSalePrice());
                BigDecimal totalPrice = unitPrice.multiply(BigDecimal.valueOf(ci.getQuantity()));

                OrderItem item = OrderItem.builder()
                        .order(savedOrder)
                        .variant(variant)
                        .productName(product.getName() + " - " + variant.getColor().getColorName() + " (" + variant.getSize() + ")")
                        .sku(variant.getSku())
                        .unitPrice(unitPrice)
                        .quantity(ci.getQuantity())
                        .totalPrice(totalPrice)
                        .build();
                orderItems.add(item);
            }
            orderItemRepository.saveAll(orderItems);
            savedOrder.setItems(orderItems);

            // 9. Ghi vết audit lịch sử tạo đơn
            statusHistoryRepository.save(OrderStatusHistory.builder()
                    .order(savedOrder)
                    .fromStatus(null)
                    .toStatus(initialStatus)
                    .updatedBy(username)
                    .note("Đơn hàng được khởi tạo qua Checkout")
                    .build());

            // 10. Xóa sạch giỏ hàng của user sau khi đặt đơn thành công
            cartItemRepository.deleteAllByUserId(user.getId());

            // 11. Xử lý sau khởi tạo theo phương thức thanh toán
            String paymentUrl = null;
            if (request.getPaymentMethod() == PaymentMethod.COD) {
                // [SỬA] Đơn COD: Kích hoạt tạo vận đơn GHN sau khi transaction DB đã commit hoàn tất
                // Tránh giữ connection DB Hikari và không block database khi mạng GHN chậm
                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            try {
                                shippingService.createShipmentForOrder(savedOrder);
                            } catch (Exception e) {
                                log.error("Tạo vận đơn GHN cho đơn COD #{} gặp sự cố: {}", savedOrder.getId(), e.getMessage());
                            }
                        }
                    });
                } else {
                    try {
                        shippingService.createShipmentForOrder(savedOrder);
                    } catch (Exception e) {
                        log.error("Tạo vận đơn GHN cho đơn COD #{} gặp sự cố: {}", savedOrder.getId(), e.getMessage());
                    }
                }
            } else if (request.getPaymentMethod() == PaymentMethod.VNPAY) {
                // Đơn VNPay: Tạo URL thanh toán
                paymentUrl = paymentService.createPaymentUrl(savedOrder, ipAddress);
            }

            OrderResponse response = orderMapper.toOrderResponse(savedOrder);
            response.setItems(orderMapper.toOrderItemResponseList(orderItems));
            response.setPaymentUrl(paymentUrl);

            log.info("Đặt hàng thành công: Order #{}", savedOrder.getId());
            return response;
        } finally {
            if (redisTemplate != null) {
                redisTemplate.delete(checkoutLockKey);
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OrderResponse> getMyOrders(String username, Pageable pageable) {
        User user = requireUser(username);
        return orderRepository.findByUserIdOrderByCreateDateDesc(user.getId(), pageable)
                .map(this::mapOrderWithItems);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponse getOrderById(String username, Long orderId) {
        User user = requireUser(username);
        Order order = orderRepository.findByIdAndUserIdWithDetails(orderId, user.getId())
                .orElseThrow(() -> new AppException(ErrorCode.ORDER_NOT_FOUND));

        return mapOrderWithItems(order);
    }

    @Override
    @Transactional
    public OrderResponse cancelOrder(String username, Long orderId, String reason) {
        User user = requireUser(username);
        // [SỬA] Khóa dòng Order bằng findByIdForUpdate để tuần tự hóa an toàn tuyệt đối với IPN / Scheduler
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new AppException(ErrorCode.ORDER_NOT_FOUND));

        if (!order.getUser().getId().equals(user.getId())) {
            throw new AppException(ErrorCode.ORDER_ACCESS_DENIED);
        }

        // Khách hàng chỉ được tự hủy khi đơn chưa giao (PENDING_PAYMENT hoặc PROCESSING)
        if (order.getOrderStatus() != OrderStatus.PENDING_PAYMENT && order.getOrderStatus() != OrderStatus.PROCESSING) {
            throw new AppException(ErrorCode.ORDER_CANNOT_BE_CANCELLED,
                    "Không thể hủy đơn hàng khi đơn đã được giao cho shipper hoặc đã kết thúc");
        }

        // Chuyển trạng thái sang CANCELLED (State machine sẽ tự động hoàn kho idempotent đúng 1 lần)
        Order cancelledOrder = orderStateMachine.transition(order, OrderStatus.CANCELLED, username,
                reason != null ? reason : "Người mua yêu cầu hủy đơn");

        // Hủy vận đơn trên GHN nếu đã tạo
        shippingService.cancelShipment(cancelledOrder, reason);

        return mapOrderWithItems(cancelledOrder);
    }

    @Override
    @Transactional
    public OrderResponse repay(String username, Long orderId, String ipAddress) {
        String paymentUrl = paymentService.repay(orderId, username, ipAddress);
        Order order = orderRepository.findByIdWithDetails(orderId)
                .orElseThrow(() -> new AppException(ErrorCode.ORDER_NOT_FOUND));

        OrderResponse res = mapOrderWithItems(order);
        res.setPaymentUrl(paymentUrl);
        return res;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OrderResponse> getOrdersForAdmin(OrderStatus status, Pageable pageable) {
        Page<Order> page = (status != null)
                ? orderRepository.findByOrderStatusOrderByCreateDateDesc(status, pageable)
                : orderRepository.findAllByOrderByCreateDateDesc(pageable);

        return page.map(this::mapOrderWithItems);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponse getOrderDetailForAdmin(Long orderId) {
        Order order = orderRepository.findByIdWithDetails(orderId)
                .orElseThrow(() -> new AppException(ErrorCode.ORDER_NOT_FOUND));
        return mapOrderWithItems(order);
    }

    @Override
    @Transactional
    public OrderResponse updateOrderStatusAdmin(Long orderId, OrderStatusUpdateRequest request, String staffUsername) {
        Order order = orderRepository.findByIdWithDetails(orderId)
                .orElseThrow(() -> new AppException(ErrorCode.ORDER_NOT_FOUND));

        Order updated = orderStateMachine.transition(order, request.getStatus(), staffUsername, request.getNote());
        return mapOrderWithItems(updated);
    }

    @Override
    @Transactional
    public Shipment retryShipmentAdmin(Long orderId) {
        return shippingService.retryShipment(orderId);
    }

    // ---------------------- Helpers ----------------------

    private User requireUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));
    }

    private OrderResponse mapOrderWithItems(Order order) {
        OrderResponse res = orderMapper.toOrderResponse(order);
        List<OrderItem> items = order.getItems();
        if (items == null || items.isEmpty()) {
            items = orderItemRepository.findByOrderId(order.getId());
        }
        res.setItems(orderMapper.toOrderItemResponseList(items));
        return res;
    }
}

