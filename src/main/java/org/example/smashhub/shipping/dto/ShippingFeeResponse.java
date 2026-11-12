package org.example.smashhub.shipping.dto;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.example.smashhub.common.enums.ShippingMethod;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class ShippingFeeResponse {
    ShippingMethod shippingMethod;
    Integer serviceId;
    String serviceName;
    BigDecimal shippingFee;
    LocalDateTime expectedDeliveryDate;
}

