package org.example.smashhub.chat.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProductAdvisoryRecommendationDto {
    private String text;
    @Builder.Default
    private List<Long> recommendedProductIds = new ArrayList<>();
}

