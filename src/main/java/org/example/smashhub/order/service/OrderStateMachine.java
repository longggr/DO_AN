package org.example.smashhub.order.service;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.example.smashhub.catalog.service.InventoryService;
import org.example.smashhub.common.enums.OrderStatus;
import org.example.smashhub.exception.AppException;
import org.example.smashhub.exception.ErrorCode;
import org.example.smashhub.order.entity.Order;
import org.example.smashhub.order.entity.OrderItem;
import org.example.smashhub.order.entity.OrderStatusHistory;
import org.example.smashhub.order.repository.OrderItemRepository;
import org.example.smashhub.order.repository.OrderRepository;
import org.example.smashhub.order.repository.OrderStatusHistoryRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * [MỚI] State machine quản lý chuyển trạng thái đơn hàng tập trung một nơi duy nhất.
 * Đảm bảo:
 * 1. Chuyển trạng thái hợp lệ theo luồng nghiệp vụ.
 * 2. Hoàn kho idempotent đúng 1 lần duy nhất khi đơn bị CANCELLED / EXPIRED.
 * 3. Ghi vết audit vào bảng order_status_history.
 */
@Component
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class OrderStateMachine {

    OrderRepository orderRepository;
    OrderItemRepository orderItemRepository;
    OrderStatusHistoryRepository statusHistoryRepository;
    InventoryService inventoryService;

    // Bảng định nghĩa các chuyển đổi trạng thái hợp lệ
    private static final Map<OrderStatus, Set<OrderStatus>> VALID_TRANSITIONS = new EnumMap<>(OrderStatus.class);

    static {
        // Đơn chờ thanh toán VNPAY: có thể được thanh toán thành công, hoặc bị hủy/hết hạn
        VALID_TRANSITIONS.put(OrderStatus.PENDING_PAYMENT,
                EnumSet.of(OrderStatus.PAID, OrderStatus.CANCELLED, OrderStatus.EXPIRED));

        // Đã thanh toán: chuyển sang xử lý đóng gói, hoặc hoàn tiền nếu hủy
        VALID_TRANSITIONS.put(OrderStatus.PAID,
                EnumSet.of(OrderStatus.PROCESSING, OrderStatus.CANCELLED, OrderStatus.REFUNDED));

        // Đang xử lý đóng gói (COD hoặc sau khi PAID): giao cho shipper, hoặc hủy
        VALID_TRANSITIONS.put(OrderStatus.PROCESSING,
                EnumSet.of(OrderStatus.SHIPPING, OrderStatus.CANCELLED));

        // Đang giao hàng: giao thành công, hoặc giao thất bại/trả hàng -> hủy
        VALID_TRANSITIONS.put(OrderStatus.SHIPPING,
                EnumSet.of(OrderStatus.DELIVERED, OrderStatus.CANCELLED));

        // Đã giao: có thể hoàn tiền nếu khách trả hàng
        VALID_TRANSITIONS.put(OrderStatus.DELIVERED,
                EnumSet.of(OrderStatus.REFUNDED));

        // Các trạng thái kết thúc (Terminal): không được chuyển tiếp
        VALID_TRANSITIONS.put(OrderStatus.CANCELLED, Collections.emptySet());
        VALID_TRANSITIONS.put(OrderStatus.EXPIRED, Collections.emptySet());
        VALID_TRANSITIONS.put(OrderStatus.REFUNDED, Collections.emptySet());
    }

    /**
     * Kiểm tra tính hợp lệ của việc chuyển trạng thái mà không thay đổi dữ liệu.
     */
    public boolean canTransition(OrderStatus from, OrderStatus to) {
        if (from == null || to == null) return false;
        if (from == to) return true; // Idempotent check
        Set<OrderStatus> allowed = VALID_TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    /**
     * Thực hiện chuyển trạng thái đơn hàng.
     *
     * @param order        Đơn hàng
     * @param targetStatus Trạng thái đích
     * @param updatedBy    Người/Hệ thống thực hiện (VD: "username", "GHN_WEBHOOK", "VNPAY_IPN", "SCHEDULER")
     * @param note         Ghi chú lý do chuyển
     * @return Đơn hàng sau khi đã chuyển trạng thái
     */
    @Transactional
    public Order transition(Order order, OrderStatus targetStatus, String updatedBy, String note) {
        // [SỬA] Khóa dòng Order bằng PESSIMISTIC_WRITE (SELECT FOR UPDATE) để đồng bộ hóa tuyệt đối
        // chống race condition giữa IPN, Scheduler hết hạn, Webhook GHN và User hủy đơn.
        Order lockedOrder = (order.getId() != null)
                ? orderRepository.findByIdForUpdate(order.getId()).orElse(order)
                : order;

        OrderStatus currentStatus = lockedOrder.getOrderStatus();

        // 1. Nếu trạng thái trùng nhau -> Idempotent, không làm gì thêm
        if (currentStatus == targetStatus) {
            log.info("Order #{} đã ở trạng thái {}, bỏ qua chuyển đổi lặp.", lockedOrder.getId(), targetStatus);
            return lockedOrder;
        }

        // 2. Kiểm tra chuyển đổi hợp lệ
        if (!canTransition(currentStatus, targetStatus)) {
            log.warn("Chuyển trạng thái không hợp lệ cho Order #{}: {} -> {}", lockedOrder.getId(), currentStatus, targetStatus);
            throw new AppException(ErrorCode.ORDER_STATUS_INVALID_TRANSITION,
                    String.format("Không thể chuyển trạng thái đơn hàng từ %s sang %s", currentStatus, targetStatus));
        }

        log.info("Order #{} chuyển trạng thái: {} -> {} bởi {}", lockedOrder.getId(), currentStatus, targetStatus, updatedBy);

        // 3. Hoàn kho idempotent đúng 1 lần khi đơn bị hủy (CANCELLED) hoặc hết hạn (EXPIRED)
        if (targetStatus == OrderStatus.CANCELLED || targetStatus == OrderStatus.EXPIRED) {
            restoreStockIdempotent(lockedOrder);
        }

        // 4. Cập nhật trạng thái Order
        lockedOrder.setOrderStatus(targetStatus);
        Order saved = orderRepository.save(lockedOrder);

        // 5. Ghi nhật ký audit
        statusHistoryRepository.save(OrderStatusHistory.builder()
                .order(saved)
                .fromStatus(currentStatus)
                .toStatus(targetStatus)
                .updatedBy(updatedBy)
                .note(note)
                .build());

        return saved;
    }

    /**
     * [SỬA] Hoàn kho an toàn: kiểm tra cờ stockRestored để không bao giờ hoàn kho 2 lần cho cùng 1 đơn.
     * TUYỆT ĐỐI KHÔNG nuốt exception. Nếu có lỗi hoàn kho thì phải throw để rollback toàn bộ transaction.
     */
    private void restoreStockIdempotent(Order order) {
        if (order.isStockRestored()) {
            log.info("Order #{} đã được hoàn kho trước đó, bỏ qua hoàn kho lặp.", order.getId());
            return;
        }

        List<OrderItem> items = order.getItems();
        if (items == null || items.isEmpty()) {
            items = orderItemRepository.findByOrderId(order.getId());
        }

        if (items != null) {
            for (OrderItem item : items) {
                // Không bọc try-catch nuốt lỗi: nếu lỗi DB thì rollback để bảo toàn tính toàn vẹn
                inventoryService.restoreStock(item.getVariant().getId(), item.getQuantity());
                log.info("Hoàn tồn kho Order #{}: Variant ID={}, Qty={}",
                        order.getId(), item.getVariant().getId(), item.getQuantity());
            }
        }
        order.setStockRestored(true);
    }
}

