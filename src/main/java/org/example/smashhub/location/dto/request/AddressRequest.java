package org.example.smashhub.location.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AddressRequest {

    @NotBlank(message = "Recipient name must not be blank")
    String recipientName;

    @NotBlank(message = "Phone number must not be blank")
    String phone;

    @NotBlank(message = "Address line must not be blank")
    String line1;

    @NotNull(message = "provinceId must not be null")
    Integer provinceId;

    @NotNull(message = "districtId must not be null")
    Integer districtId;

    @NotBlank(message = "wardCode must not be blank")
    String wardCode;

    Boolean primary;
}

