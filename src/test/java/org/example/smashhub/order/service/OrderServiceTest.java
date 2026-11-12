package org.example.smashhub.order.service;

import org.example.smashhub.cart.entity.CartItem;
import org.example.smashhub.cart.repository.CartItemRepository;
import org.example.smashhub.catalog.entity.Product;
import org.example.smashhub.catalog.entity.ProductColor;
import org.example.smashhub.catalog.entity.ProductVariant;
import org.example.smashhub.catalog.service.InventoryService;
import org.example.smashhub.common.enums.AddressPriority;
import org.example.smashhub.common.enums.OrderStatus;
import org.example.smashhub.common.enums.PaymentMethod;
import org.example.smashhub.common.enums.ProductStatus;
import org.example.smashhub.common.enums.ShippingMethod;
import org.example.smashhub.common.enums.VariantStatus;
import org.example.smashhub.exception.AppException;
import org.example.smashhub.exception.ErrorCode;
import org.example.smashhub.location.entity.Address;
import org.example.smashhub.location.entity.District;
import org.example.smashhub.location.entity.Province;
import org.example.smashhub.location.entity.Ward;
import org.example.smashhub.location.repository.AddressRepository;
import org.example.smashhub.order.dto.request.CheckoutRequest;
import org.example.smashhub.order.dto.response.OrderResponse;
import org.example.smashhub.order.entity.Order;
import org.example.smashhub.order.mapper.OrderMapper;
import org.example.smashhub.order.repository.OrderItemRepository;
import org.example.smashhub.order.repository.OrderRepository;
import org.example.smashhub.order.repository.OrderStatusHistoryRepository;
import org.example.smashhub.order.service.impl.OrderServiceImpl;
import org.example.smashhub.payment.service.PaymentService;
import org.example.smashhub.shipping.dto.ShippingFeeResponse;
import org.example.smashhub.shipping.service.ShippingService;
import org.example.smashhub.user.entity.User;
import org.example.smashhub.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    UserRepository userRepository;

    @Mock
    AddressRepository addressRepository;

    @Mock
    CartItemRepository cartItemRepository;

    @Mock
    OrderRepository orderRepository;

    @Mock
    OrderItemRepository orderItemRepository;

    @Mock
    OrderStatusHistoryRepository statusHistoryRepository;

    @Mock
    InventoryService inventoryService;

    @Mock
    ShippingService shippingService;

    @Mock
    PaymentService paymentService;

    @Mock
    OrderStateMachine orderStateMachine;

    @Mock
    OrderMapper orderMapper;

    @InjectMocks
    OrderServiceImpl orderService;

    User sampleUser;
    Address sampleAddress;
    CartItem sampleCartItem;

    @BeforeEach
    void setUp() {
        sampleUser = new User();
        sampleUser.setId(1L);
        sampleUser.setUsername("testuser");
        sampleUser.setPhone("0987654321");

        Province province = Province.builder().id(201).name("Hà Nội").build();
        District district = District.builder().id(1482).province(province).name("Ba Đình").build();
        Ward ward = Ward.builder().code("1A0101").district(district).name("Phúc Xá").build();

        sampleAddress = Address.builder()
                .user(sampleUser)
                .recipientName("Nguyen Van A")
                .phone("0987654321")
                .line1("123 Pho Hue")
                .province(province)
                .district(district)
                .ward(ward)
                .primaryAddress(AddressPriority.PRIMARY)
                .build();
        sampleAddress.setId(5L);

        Product product = new Product();
        product.setId(10L);
        product.setName("Yonex Astrox 100ZZ");
        product.setPrice(new BigDecimal("4000000.00"));
        product.setProductStatus(ProductStatus.ACTIVE);

        ProductColor color = ProductColor.builder().product(product).colorName("Kurenai").build();
        color.setId(20L);

        ProductVariant variant = ProductVariant.builder()
                .color(color)
                .sku("AX100ZZ-4U")
                .size("4U-G5")
                .stock(10)
                .active(VariantStatus.ACTIVE)
                .build();
        variant.setId(30L);

        sampleCartItem = CartItem.builder()
                .user(sampleUser)
                .variant(variant)
                .quantity(2)
                .build();
        sampleCartItem.setId(100L);

        lenient().when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(sampleUser));
    }

    @Test
    @DisplayName("Checkout khi giỏ hàng rỗng phải ném CART_EMPTY")
    void testCheckoutEmptyCart() {
        when(cartItemRepository.findByUserIdWithDetails(1L)).thenReturn(List.of());

        CheckoutRequest request = CheckoutRequest.builder()
                .addressId(5L)
                .paymentMethod(PaymentMethod.COD)
                .shippingMethod(ShippingMethod.STANDARD)
                .build();

        assertThrows(AppException.class, () ->
                orderService.checkout("testuser", request, "127.0.0.1")
        );
        verify(inventoryService, never()).deductStock(any(), anyInt());
    }

    @Test
    @DisplayName("Checkout khi tồn kho không đủ phải ném exception và rollback")
    void testCheckoutInsufficientStock() {
        when(cartItemRepository.findByUserIdWithDetails(1L)).thenReturn(List.of(sampleCartItem));
        when(addressRepository.findByIdAndUserId(5L, 1L)).thenReturn(Optional.of(sampleAddress));

        ShippingFeeResponse feeResponse = ShippingFeeResponse.builder()
                .shippingFee(new BigDecimal("30000.00"))
                .build();
        when(shippingService.calculateFee(eq("testuser"), any())).thenReturn(feeResponse);

        // Giả lập trừ kho thất bại do không đủ tồn kho
        doThrow(new AppException(ErrorCode.PRODUCT_VARIANT_INSUFFICIENT_STOCK))
                .when(inventoryService).deductStock(30L, 2);

        CheckoutRequest request = CheckoutRequest.builder()
                .addressId(5L)
                .paymentMethod(PaymentMethod.COD)
                .shippingMethod(ShippingMethod.STANDARD)
                .build();

        assertThrows(AppException.class, () ->
                orderService.checkout("testuser", request, "127.0.0.1")
        );

        // Đảm bảo không lưu Order và không xóa Cart
        verify(orderRepository, never()).save(any());
        verify(cartItemRepository, never()).deleteAllByUserId(any());
    }

    @Test
    @DisplayName("Checkout thành công đơn COD: Trừ kho, tạo Order, xóa Cart và tạo vận đơn GHN")
    void testCheckoutCodSuccess() {
        when(cartItemRepository.findByUserIdWithDetails(1L)).thenReturn(List.of(sampleCartItem));
        when(addressRepository.findByIdAndUserId(5L, 1L)).thenReturn(Optional.of(sampleAddress));

        ShippingFeeResponse feeResponse = ShippingFeeResponse.builder()
                .shippingFee(new BigDecimal("30000.00"))
                .build();
        when(shippingService.calculateFee(eq("testuser"), any())).thenReturn(feeResponse);

        when(orderRepository.save(any(Order.class))).thenAnswer(i -> {
            Order o = i.getArgument(0);
            o.setId(999L);
            return o;
        });

        when(orderMapper.toOrderResponse(any())).thenReturn(OrderResponse.builder().id(999L).build());

        CheckoutRequest request = CheckoutRequest.builder()
                .addressId(5L)
                .paymentMethod(PaymentMethod.COD)
                .shippingMethod(ShippingMethod.STANDARD)
                .build();

        OrderResponse res = orderService.checkout("testuser", request, "127.0.0.1");

        assertNotNull(res);
        assertEquals(999L, res.getId());

        // Đã trừ kho variant ID 30, số lượng 2
        verify(inventoryService, times(1)).deductStock(30L, 2);
        // Đã xóa giỏ hàng
        verify(cartItemRepository, times(1)).deleteAllByUserId(1L);
        // Đã tạo vận đơn GHN cho đơn COD
        verify(shippingService, times(1)).createShipmentForOrder(any());
    }
}
