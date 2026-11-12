package org.example.smashhub.shipping.service;

import org.example.smashhub.cart.repository.CartItemRepository;
import org.example.smashhub.catalog.repository.ProductAttributeRepository;
import org.example.smashhub.common.enums.OrderStatus;
import org.example.smashhub.config.GhnConfig;
import org.example.smashhub.exception.AppException;
import org.example.smashhub.location.repository.AddressRepository;
import org.example.smashhub.order.entity.Order;
import org.example.smashhub.order.repository.OrderRepository;
import org.example.smashhub.order.service.OrderStateMachine;
import org.example.smashhub.shipping.client.GhnClient;
import org.example.smashhub.shipping.dto.GhnWebhookPayload;
import org.example.smashhub.shipping.entity.Shipment;
import org.example.smashhub.shipping.repository.ShipmentRepository;
import org.example.smashhub.shipping.service.impl.ShippingServiceImpl;
import org.example.smashhub.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShippingServiceTest {

    @Mock
    GhnConfig ghnConfig;

    @Mock
    GhnClient ghnClient;

    @Mock
    ShipmentRepository shipmentRepository;

    @Mock
    OrderRepository orderRepository;

    @Mock
    CartItemRepository cartItemRepository;

    @Mock
    AddressRepository addressRepository;

    @Mock
    ProductAttributeRepository attributeRepository;

    @Mock
    UserRepository userRepository;

    @Mock
    OrderStateMachine orderStateMachine;

    @InjectMocks
    ShippingServiceImpl shippingService;

    final String WEBHOOK_TOKEN = "TEST_SECRET_WEBHOOK_TOKEN";
    Shipment sampleShipment;
    Order sampleOrder;

    @BeforeEach
    void setUp() {
        lenient().when(ghnConfig.getWebhookToken()).thenReturn(WEBHOOK_TOKEN);

        sampleOrder = Order.builder()
                .orderStatus(OrderStatus.PROCESSING)
                .build();
        sampleOrder.setId(201L);

        sampleShipment = Shipment.builder()
                .order(sampleOrder)
                .orderCode("GHN123456")
                .clientOrderCode("ORDER-201")
                .status("ready_to_pick")
                .build();
    }

    @Test
    @DisplayName("Webhook: Sai secret token phải bị từ chối bằng AppException")
    void testWebhookUnauthorized() {
        GhnWebhookPayload payload = GhnWebhookPayload.builder()
                .OrderCode("GHN123456")
                .Status("delivering")
                .build();

        assertThrows(AppException.class, () ->
                shippingService.handleWebhook("WRONG_TOKEN", payload)
        );
        verify(shipmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("Webhook: Cập nhật delivered phải chuyển trạng thái đơn sang DELIVERED")
    void testWebhookDelivered() {
        GhnWebhookPayload payload = GhnWebhookPayload.builder()
                .OrderCode("GHN123456")
                .Status("delivered")
                .StatusName("Giao hàng thành công")
                .build();

        when(shipmentRepository.findByOrderCodeWithOrder("GHN123456")).thenReturn(Optional.of(sampleShipment));

        shippingService.handleWebhook(WEBHOOK_TOKEN, payload);

        verify(orderStateMachine, times(1)).transition(eq(sampleOrder), eq(OrderStatus.DELIVERED), eq("GHN_WEBHOOK"), any());
        verify(shipmentRepository, times(1)).save(sampleShipment);
    }

    @Test
    @DisplayName("Webhook: Không được lùi trạng thái khi đơn đã DELIVERED")
    void testWebhookDoesNotRegressDeliveredOrder() {
        sampleOrder.setOrderStatus(OrderStatus.DELIVERED);

        GhnWebhookPayload latePayload = GhnWebhookPayload.builder()
                .OrderCode("GHN123456")
                .Status("delivering") // Gói tin trễ hoặc lặp
                .StatusName("Đang giao hàng")
                .build();

        when(shipmentRepository.findByOrderCodeWithOrder("GHN123456")).thenReturn(Optional.of(sampleShipment));

        shippingService.handleWebhook(WEBHOOK_TOKEN, latePayload);

        // Không bao giờ được gọi transition chuyển lùi về SHIPPING
        verify(orderStateMachine, never()).transition(any(), eq(OrderStatus.SHIPPING), any(), any());
    }
}

