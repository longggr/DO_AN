package org.example.smashhub.shipping.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.example.smashhub.common.enums.ShippingMethod;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class ShippingFeeRequest {

    Long addressId;      // Hoặc truyền toDistrictId + toWardCode trực tiếp
    Integer toDistrictId;
    String toWardCode;

    @NotNull(message = "shippingMethod must not be null")
    ShippingMethod shippingMethod; // STANDARD hoặc EXPRESS
}

