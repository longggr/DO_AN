package org.example.smashhub.chat.ai.dto.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductDetailDto implements Serializable {
    private Long id;
    private String name;
    private String slug;
    private String brand;
    private String category;
    private BigDecimal price;
    private BigDecimal salePrice;
    private int stock;
    private boolean inStock;
    private String thumbnailUrl;
    @Builder.Default
    private Map<String, String> attributes = new HashMap<>();
    private String description;
}

