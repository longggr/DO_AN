package org.example.smashhub.cart.dto.response;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.example.smashhub.common.enums.InventoryStatus;
import org.example.smashhub.common.enums.ProductStatus;
import org.example.smashhub.common.enums.VariantStatus;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class CartItemResponse {

    Long id;
    Long variantId;
    String sku;
    String size;
    Integer quantity;
    Integer availableStock;
    VariantStatus variantStatus;
    InventoryStatus inventoryStatus;

    Long productId;
    String productName;
    String productSlug;
    ProductStatus productStatus;
    BigDecimal unitPrice;
    BigDecimal salePrice;
    BigDecimal effectivePrice;
    BigDecimal lineTotal;

    Long colorId;
    String colorName;
    String hexCode;
    String thumbnailUrl;

    boolean stockInsufficient;
    boolean unavailable;
}
