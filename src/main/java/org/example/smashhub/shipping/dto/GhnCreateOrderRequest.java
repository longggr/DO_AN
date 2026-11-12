package org.example.smashhub.shipping.dto;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class GhnCreateOrderRequest {
    String clientOrderCode;
    String toName;
    String toPhone;
    String toAddress;
    Integer toDistrictId;
    String toWardCode;
    Integer weight;
    Integer length;
    Integer width;
    Integer height;
    Integer serviceId;
    Integer serviceTypeId;
    BigDecimal codAmount;
    BigDecimal insuranceValue;
    String note;
    List<GhnItem> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE)
    public static class GhnItem {
        String name;
        String code;
        Integer quantity;
        Integer price;
        Integer weight;
    }
}

