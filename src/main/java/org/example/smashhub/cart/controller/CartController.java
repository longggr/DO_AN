package org.example.smashhub.cart.controller;

import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.example.smashhub.cart.dto.request.AddCartItemRequest;
import org.example.smashhub.cart.dto.request.MergeCartRequest;
import org.example.smashhub.cart.dto.request.UpdateCartItemRequest;
import org.example.smashhub.cart.dto.response.CartMutationResponse;
import org.example.smashhub.cart.service.CartService;
import org.example.smashhub.common.response.ApiResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cart API — user (JWT) hoặc guest ({@code X-Cart-Token}).
 * <p>
 * Guest: lần đầu add không gửi token → server tạo UUID, trả header {@code X-Cart-Token}
 * và body {@code result.cartToken}. Client lưu lại gửi các request sau.
 * <p>
 * Sau login: {@code POST /cart/merge} kèm JWT + optional {@code X-Cart-Token} để gộp Redis → DB.
 */
@RestController
@RequestMapping("/cart")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class CartController {

    public static final String CART_TOKEN_HEADER = "X-Cart-Token";

    CartService cartService;

    private String currentUsernameOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return null;
        }
        String name = auth.getName();
        if (name == null || name.isBlank() || "anonymousUser".equals(name)) {
            return null;
        }
        return name;
    }

    private ResponseEntity<ApiResponse<CartMutationResponse>> ok(CartMutationResponse result, String message) {
        ApiResponse<CartMutationResponse> body = ApiResponse.<CartMutationResponse>builder()
                .result(result)
                .message(message)
                .build();
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (result.getCartToken() != null && !result.getCartToken().isBlank()) {
            builder.header(CART_TOKEN_HEADER, result.getCartToken());
        }
        return builder.body(body);
    }

    private ResponseEntity<ApiResponse<CartMutationResponse>> ok(CartMutationResponse result) {
        return ok(result, null);
    }

    @GetMapping
    public ResponseEntity<ApiResponse<CartMutationResponse>> getCart(
            @RequestHeader(value = CART_TOKEN_HEADER, required = false) String cartToken) {
        return ok(cartService.getCart(currentUsernameOrNull(), cartToken));
    }

    @PostMapping(value = "/items", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<CartMutationResponse>> addItemJson(
            @RequestHeader(value = CART_TOKEN_HEADER, required = false) String cartToken,
            @RequestBody @Valid AddCartItemRequest request) {
        return ok(cartService.addItem(currentUsernameOrNull(), cartToken, request), "Item added to cart");
    }

    @PostMapping(value = "/items", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<ApiResponse<CartMutationResponse>> addItemForm(
            @RequestHeader(value = CART_TOKEN_HEADER, required = false) String cartToken,
            @ModelAttribute @Valid AddCartItemRequest request) {
        return ok(cartService.addItem(currentUsernameOrNull(), cartToken, request), "Item added to cart");
    }

    @PutMapping(value = "/items/{variantId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<CartMutationResponse>> updateItemJson(
            @PathVariable Long variantId,
            @RequestHeader(value = CART_TOKEN_HEADER, required = false) String cartToken,
            @RequestBody @Valid UpdateCartItemRequest request) {
        return ok(cartService.updateItem(currentUsernameOrNull(), cartToken, variantId, request),
                "Cart item updated");
    }

    @PutMapping(value = "/items/{variantId}", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<ApiResponse<CartMutationResponse>> updateItemForm(
            @PathVariable Long variantId,
            @RequestHeader(value = CART_TOKEN_HEADER, required = false) String cartToken,
            @ModelAttribute @Valid UpdateCartItemRequest request) {
        return ok(cartService.updateItem(currentUsernameOrNull(), cartToken, variantId, request),
                "Cart item updated");
    }

    @DeleteMapping("/items/{variantId}")
    public ResponseEntity<ApiResponse<CartMutationResponse>> removeItem(
            @PathVariable Long variantId,
            @RequestHeader(value = CART_TOKEN_HEADER, required = false) String cartToken) {
        return ok(cartService.removeItem(currentUsernameOrNull(), cartToken, variantId), "Cart item removed");
    }

    @DeleteMapping
    public ResponseEntity<ApiResponse<CartMutationResponse>> clearCart(
            @RequestHeader(value = CART_TOKEN_HEADER, required = false) String cartToken) {
        return ok(cartService.clearCart(currentUsernameOrNull(), cartToken), "Cart cleared");
    }

    /**
     * Chỉ user đã login. Gộp guest Redis (X-Cart-Token) và/hoặc body.items vào DB, xóa Redis.
     */
    @PostMapping(value = "/merge", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<CartMutationResponse>> mergeCartJson(
            @RequestHeader(value = CART_TOKEN_HEADER, required = false) String cartToken,
            @RequestBody @Valid MergeCartRequest request) {
        String username = currentUsernameOrNull();
        return ok(cartService.mergeCart(username, cartToken, request), "Cart merged successfully");
    }

    @PostMapping(value = "/merge", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<ApiResponse<CartMutationResponse>> mergeCartForm(
            @RequestHeader(value = CART_TOKEN_HEADER, required = false) String cartToken,
            @ModelAttribute @Valid MergeCartRequest request) {
        String username = currentUsernameOrNull();
        return ok(cartService.mergeCart(username, cartToken, request), "Cart merged successfully");
    }
}
