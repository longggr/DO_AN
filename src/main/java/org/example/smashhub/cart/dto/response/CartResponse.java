package org.example.smashhub.cart.dto.response;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class CartResponse {

    @Builder.Default
    List<CartItemResponse> items = Collections.emptyList();

    int totalItems;
    int totalQuantity;
    BigDecimal subtotal;
    int issueCount;
}
