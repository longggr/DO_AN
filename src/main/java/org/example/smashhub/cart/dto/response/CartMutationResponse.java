package org.example.smashhub.cart.dto.response;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

/**
 * Bọc CartResponse + cartToken (guest).
 * User đã login: cartToken = null.
 * Guest: luôn trả token để client lưu header X-Cart-Token.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class CartMutationResponse {
    CartResponse cart;
    /** Token giỏ khách; null nếu đang dùng giỏ user (JWT). */
    String cartToken;
}
