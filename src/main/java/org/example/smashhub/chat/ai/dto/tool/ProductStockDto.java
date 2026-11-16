package org.example.smashhub.chat.ai.dto.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductStockDto implements Serializable {
    private Long productId;
    private String productName;
    private int totalStock;
    private boolean inStock;
    @Builder.Default
    private List<VariantStockDto> variants = new ArrayList<>();
}

