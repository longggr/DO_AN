package org.example.smashhub.cart.service;

import org.example.smashhub.cart.dto.request.AddCartItemRequest;
import org.example.smashhub.cart.dto.request.MergeCartRequest;
import org.example.smashhub.cart.dto.response.CartMutationResponse;
import org.example.smashhub.cart.entity.CartItem;
import org.example.smashhub.cart.guest.GuestCartStore;
import org.example.smashhub.cart.repository.CartItemRepository;
import org.example.smashhub.cart.service.impl.CartServiceImpl;
import org.example.smashhub.catalog.entity.Product;
import org.example.smashhub.catalog.entity.ProductColor;
import org.example.smashhub.catalog.entity.ProductVariant;
import org.example.smashhub.catalog.repository.ProductImageRepository;
import org.example.smashhub.catalog.repository.ProductVariantRepository;
import org.example.smashhub.common.enums.InventoryStatus;
import org.example.smashhub.common.enums.ProductStatus;
import org.example.smashhub.common.enums.VariantStatus;
import org.example.smashhub.user.entity.User;
import org.example.smashhub.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CartServiceImplTest {

    @Mock
    CartItemRepository cartItemRepository;

    @Mock
    ProductVariantRepository variantRepository;

    @Mock
    ProductImageRepository productImageRepository;

    @Mock
    UserRepository userRepository;

    @Mock
    GuestCartStore guestCartStore;

    @InjectMocks
    CartServiceImpl cartService;

    @Test
    void mergeCart_shouldFallbackToRequestItems_whenGuestRedisIsEmpty() {
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(guestCartStore.get("guest-token")).thenReturn(List.of());
        when(cartItemRepository.findByUserIdWithDetails(1L)).thenReturn(List.of());

        Product product = new Product();
        product.setId(7L);
        product.setName("Yonex Racket");
        product.setPrice(new BigDecimal("2500000"));
        product.setProductStatus(ProductStatus.ACTIVE);

        ProductColor color = ProductColor.builder().product(product).colorName("Black").build();
        color.setId(11L);

        ProductVariant variant = ProductVariant.builder()
                .sku("YX-99")
                .size("4U")
                .stock(10)
                .active(VariantStatus.ACTIVE)
                .inventoryStatus(InventoryStatus.IN_STOCK)
                .color(color)
                .build();
        variant.setId(99L);

        when(variantRepository.findById(99L)).thenReturn(Optional.of(variant));
        when(cartItemRepository.findByUserIdAndVariantId(1L, 99L)).thenReturn(Optional.empty());
        when(cartItemRepository.save(any(CartItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MergeCartRequest request = MergeCartRequest.builder()
                .items(List.of(AddCartItemRequest.builder()
                        .variantId(99L)
                        .quantity(2)
                        .build()))
                .build();

        CartMutationResponse response = cartService.mergeCart("alice", "guest-token", request);

        assertNotNull(response);
        assertNull(response.getCartToken());
        verify(cartItemRepository).save(any(CartItem.class));
        verify(guestCartStore).clear("guest-token");
    }
}
