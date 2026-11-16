package org.example.smashhub.chat.ai.dto.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VariantStockDto implements Serializable {
    private String sku;
    private String size;
    private String colorName;
    private int stock;
    private boolean available;
}

