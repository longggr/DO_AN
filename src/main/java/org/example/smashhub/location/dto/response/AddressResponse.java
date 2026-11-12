package org.example.smashhub.location.dto.response;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.example.smashhub.common.enums.AddressPriority;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AddressResponse {

    Long id;
    String recipientName;
    String phone;
    String line1;

    Integer provinceId;
    String provinceName;

    Integer districtId;
    String districtName;

    String wardCode;
    String wardName;

    AddressPriority primaryAddress;
    LocalDateTime createDate;
    LocalDateTime updateDate;
}

