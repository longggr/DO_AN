package org.example.smashhub.cart.service;

import org.example.smashhub.cart.dto.request.AddCartItemRequest;
import org.example.smashhub.cart.dto.request.MergeCartRequest;
import org.example.smashhub.cart.dto.request.UpdateCartItemRequest;
import org.example.smashhub.cart.dto.response.CartMutationResponse;

/**
 * Cart hỗ trợ 2 chế độ:
 * <ul>
 *   <li><b>User</b> (JWT): username != null → persist DB {@code cart_items}</li>
 *   <li><b>Guest</b> (X-Cart-Token): lưu Redis {@code cart:guest:{token}}</li>
 * </ul>
 * {@code mergeCart} chỉ dùng khi đã login: gộp guest Redis (hoặc payload) vào DB rồi xóa Redis.
 */
public interface CartService {

    CartMutationResponse getCart(String username, String cartToken);

    CartMutationResponse addItem(String username, String cartToken, AddCartItemRequest request);

    CartMutationResponse updateItem(String username, String cartToken, Long variantId, UpdateCartItemRequest request);

    CartMutationResponse removeItem(String username, String cartToken, Long variantId);

    CartMutationResponse clearCart(String username, String cartToken);

    /**
     * User đã login: gộp giỏ khách vào DB.
     * Ưu tiên {@code cartToken} (Redis); nếu không có thì dùng {@code request.items}.
     * Sau merge xóa Redis guest key.
     */
    CartMutationResponse mergeCart(String username, String cartToken, MergeCartRequest request);
}
