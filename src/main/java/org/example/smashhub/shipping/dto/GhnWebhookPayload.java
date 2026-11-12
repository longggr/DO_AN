package org.example.smashhub.shipping.dto;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.math.BigDecimal;

/**
 * [MỚI] Payload webhook do GHN gửi tới khi vận đơn có biến động trạng thái.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class GhnWebhookPayload {

    String OrderCode;
    String Status;
    String StatusName;
    String ClientOrderCode;
    BigDecimal CODAmount;
    BigDecimal TotalFee;
    String Reason;
    String Description;
    String ShipperName;
    String ShipperPhone;
}

