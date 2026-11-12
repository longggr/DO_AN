package org.example.smashhub.order.dto.response;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.example.smashhub.common.enums.OrderStatus;
import org.example.smashhub.common.enums.PaymentMethod;
import org.example.smashhub.common.enums.ShippingMethod;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class OrderResponse {

    Long id;
    OrderStatus orderStatus;
    PaymentMethod paymentMethod;
    ShippingMethod shippingMethod;

    String shippingRecipientName;
    String shippingPhone;
    String shippingLine1;

    Integer shippingProvinceId;
    String shippingProvinceName;

    Integer shippingDistrictId;
    String shippingDistrictName;

    String shippingWardCode;
    String shippingWardName;

    BigDecimal subtotal;
    BigDecimal shippingCost;
    BigDecimal discount;
    BigDecimal total;

    String note;
    boolean stockRestored;

    String paymentUrl; // Chỉ có khi thanh toán qua VNPay và đơn đang PENDING_PAYMENT

    List<OrderItemResponse> items;
    LocalDateTime createDate;
    LocalDateTime updateDate;
}

