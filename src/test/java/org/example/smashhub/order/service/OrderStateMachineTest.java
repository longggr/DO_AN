package org.example.smashhub.order.service;

import org.example.smashhub.catalog.service.InventoryService;
import org.example.smashhub.catalog.entity.ProductVariant;
import org.example.smashhub.common.enums.OrderStatus;
import org.example.smashhub.exception.AppException;
import org.example.smashhub.order.entity.Order;
import org.example.smashhub.order.entity.OrderItem;
import org.example.smashhub.order.repository.OrderItemRepository;
import org.example.smashhub.order.repository.OrderRepository;
import org.example.smashhub.order.repository.OrderStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderStateMachineTest {

    @Mock
    OrderRepository orderRepository;

    @Mock
    OrderItemRepository orderItemRepository;

    @Mock
    OrderStatusHistoryRepository statusHistoryRepository;

    @Mock
    InventoryService inventoryService;

    @InjectMocks
    OrderStateMachine orderStateMachine;

    Order sampleOrder;

    @BeforeEach
    void setUp() {
        ProductVariant variant = ProductVariant.builder().build();
        variant.setId(10L);

        OrderItem item = OrderItem.builder()
                .variant(variant)
                .quantity(2)
                .build();

        sampleOrder = Order.builder()
                .orderStatus(OrderStatus.PENDING_PAYMENT)
                .stockRestored(false)
                .items(new ArrayList<>(List.of(item)))
                .build();
        sampleOrder.setId(1L);

        lenient().when(orderRepository.findByIdForUpdate(anyLong())).thenAnswer(invocation -> Optional.of(sampleOrder));
        lenient().when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("Chuyển trạng thái hợp lệ: PENDING_PAYMENT -> PAID -> PROCESSING -> SHIPPING -> DELIVERED")
    void testValidTransitions() {
        Order o1 = orderStateMachine.transition(sampleOrder, OrderStatus.PAID, "TEST", "Thanh toán thành công");
        assertEquals(OrderStatus.PAID, o1.getOrderStatus());

        Order o2 = orderStateMachine.transition(o1, OrderStatus.PROCESSING, "TEST", "Chuẩn bị hàng");
        assertEquals(OrderStatus.PROCESSING, o2.getOrderStatus());

        Order o3 = orderStateMachine.transition(o2, OrderStatus.SHIPPING, "TEST", "Bàn giao vận chuyển");
        assertEquals(OrderStatus.SHIPPING, o3.getOrderStatus());

        Order o4 = orderStateMachine.transition(o3, OrderStatus.DELIVERED, "TEST", "Giao hàng thành công");
        assertEquals(OrderStatus.DELIVERED, o4.getOrderStatus());

        verify(statusHistoryRepository, times(4)).save(any());
    }

    @Test
    @DisplayName("Chuyển trạng thái không hợp lệ: DELIVERED -> PENDING_PAYMENT phải ném AppException")
    void testInvalidTransitionThrowsException() {
        sampleOrder.setOrderStatus(OrderStatus.DELIVERED);

        assertThrows(AppException.class, () ->
                orderStateMachine.transition(sampleOrder, OrderStatus.PENDING_PAYMENT, "TEST", "Hack status")
        );
    }

    @Test
    @DisplayName("Chuyển sang CANCELLED phải hoàn kho đúng 1 lần (idempotent)")
    void testRestoreStockOnceOnCancel() {
        Order cancelled = orderStateMachine.transition(sampleOrder, OrderStatus.CANCELLED, "TEST", "Hủy đơn");

        assertTrue(cancelled.isStockRestored());
        assertEquals(OrderStatus.CANCELLED, cancelled.getOrderStatus());
        verify(inventoryService, times(1)).restoreStock(10L, 2);

        // Gọi lại chuyển sang CANCELLED lần nữa (idempotent)
        orderStateMachine.transition(cancelled, OrderStatus.CANCELLED, "TEST", "Hủy lặp");
        // Số lần gọi hoàn kho vẫn chỉ là 1, không được hoàn thêm
        verify(inventoryService, times(1)).restoreStock(10L, 2);
    }
}

