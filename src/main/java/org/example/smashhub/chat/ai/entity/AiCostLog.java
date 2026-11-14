package org.example.smashhub.chat.ai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldDefaults;
import org.example.smashhub.common.persistence.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "ai_cost_logs")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AiCostLog extends BaseEntity {

    @Column(name = "log_date", nullable = false)
    LocalDate logDate;

    @Column(name = "model_name", nullable = false, length = 50)
    String modelName;

    @Column(name = "input_tokens", nullable = false)
    @Builder.Default
    long inputTokens = 0L;

    @Column(name = "output_tokens", nullable = false)
    @Builder.Default
    long outputTokens = 0L;

    @Column(name = "estimated_cost_usd", nullable = false, precision = 12, scale = 6)
    @Builder.Default
    BigDecimal estimatedCostUsd = BigDecimal.ZERO;

    @Column(name = "request_count", nullable = false)
    @Builder.Default
    int requestCount = 0;
}

