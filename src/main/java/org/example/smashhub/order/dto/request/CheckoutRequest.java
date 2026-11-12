package org.example.smashhub.order.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.example.smashhub.common.enums.PaymentMethod;
import org.example.smashhub.common.enums.ShippingMethod;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class CheckoutRequest {

    @NotNull(message = "addressId must not be null")
    Long addressId;

    @NotNull(message = "shippingMethod must not be null")
    ShippingMethod shippingMethod;

    @NotNull(message = "paymentMethod must not be null")
    PaymentMethod paymentMethod;

    String note;
}

