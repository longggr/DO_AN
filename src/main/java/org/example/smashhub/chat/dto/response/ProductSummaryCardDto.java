package org.example.smashhub.chat.dto.response;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class ProductSummaryCardDto implements Serializable {
    Long id;
    String name;
    String slug;
    BigDecimal price;
    BigDecimal salePrice;
    String thumbnailUrl;
}

