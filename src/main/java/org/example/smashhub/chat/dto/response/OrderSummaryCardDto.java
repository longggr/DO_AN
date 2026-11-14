package org.example.smashhub.chat.dto.response;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.example.smashhub.common.enums.OrderStatus;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class OrderSummaryCardDto implements Serializable {
    Long id;
    OrderStatus orderStatus;
    BigDecimal total;
    LocalDateTime createdAt;
    int itemsCount;
}

