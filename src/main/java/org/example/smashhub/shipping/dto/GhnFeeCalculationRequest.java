package org.example.smashhub.shipping.dto;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class GhnFeeCalculationRequest {
    Integer fromDistrictId;
    String fromWardCode;
    Integer toDistrictId;
    String toWardCode;
    Integer serviceId;
    Integer serviceTypeId;
    Integer weight; // grams
    Integer length; // cm
    Integer width;  // cm
    Integer height; // cm
    BigDecimal insuranceValue; // VND
}

