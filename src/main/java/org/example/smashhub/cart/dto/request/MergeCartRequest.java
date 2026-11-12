package org.example.smashhub.cart.dto.request;

import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.util.List;

/**
 * Body merge (optional nếu đã gửi X-Cart-Token trỏ Redis guest cart).
 * Có thể gửi cả hai: server ưu tiên Redis token, fallback items.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class MergeCartRequest {

    @Valid
    List<AddCartItemRequest> items;
}
