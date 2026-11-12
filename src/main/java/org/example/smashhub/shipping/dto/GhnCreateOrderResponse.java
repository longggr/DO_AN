package org.example.smashhub.shipping.dto;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class GhnCreateOrderResponse {
    String orderCode;
    BigDecimal totalFee;
    LocalDateTime expectedDeliveryDate;
    String rawResponse;
}

