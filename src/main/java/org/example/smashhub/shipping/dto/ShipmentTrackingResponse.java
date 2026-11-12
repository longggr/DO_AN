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
public class ShipmentTrackingResponse {
    Long orderId;
    String orderCode;
    String clientOrderCode;
    String status;
    String statusName;
    BigDecimal fee;
    BigDecimal codAmount;
    LocalDateTime expectedDeliveryDate;
    String printUrl;
}

