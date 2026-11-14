package org.example.smashhub.chat.ai.limiter;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.example.smashhub.chat.ai.config.AiProperties;
import org.example.smashhub.chat.ai.entity.AiCostLog;
import org.example.smashhub.chat.ai.repository.AiCostLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AiBudgetTracker {

    final StringRedisTemplate redisTemplate;
    final AiProperties aiProperties;
    final AiCostLogRepository aiCostLogRepository;

    @Autowired(required = false)
    MeterRegistry meterRegistry;

    final AtomicBoolean warningLoggedToday = new AtomicBoolean(false);
    final AtomicBoolean cutoffLoggedToday = new AtomicBoolean(false);

    static final String KEY_DAILY_COST_PREFIX = "smashhub:ai:cost:daily:";
    static final String KEY_DAILY_TOKENS_IN_PREFIX = "smashhub:ai:tokens:in:";
    static final String KEY_DAILY_TOKENS_OUT_PREFIX = "smashhub:ai:tokens:out:";
    static final String KEY_DAILY_REQUESTS_PREFIX = "smashhub:ai:requests:daily:";

    public static class BudgetCheckResult {
        private final boolean allowed;
        private final BigDecimal currentCostUsd;
        private final BigDecimal limitUsd;
        private final double percent;

        public BudgetCheckResult(boolean allowed, BigDecimal currentCostUsd, BigDecimal limitUsd, double percent) {
            this.allowed = allowed;
            this.currentCostUsd = currentCostUsd;
            this.limitUsd = limitUsd;
            this.percent = percent;
        }

        public boolean isAllowed() { return allowed; }
        public BigDecimal getCurrentCostUsd() { return currentCostUsd; }
        public BigDecimal getLimitUsd() { return limitUsd; }
        public double getPercent() { return percent; }
    }

    private String getTodayKey() {
        return LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    /**
     * Kiểm tra trạng thái ngân sách hôm nay so với ngưỡng cấu hình (80% warning, 100% cutoff).
     */
    public BudgetCheckResult checkDailyBudget() {
        String today = getTodayKey();
        String costKey = KEY_DAILY_COST_PREFIX + today;
        String val = redisTemplate.opsForValue().get(costKey);
        BigDecimal currentCost = val != null ? new BigDecimal(val) : BigDecimal.ZERO;
        BigDecimal dailyLimit = aiProperties.getBudget().getDailyLimitUsd();

        if (dailyLimit == null || dailyLimit.compareTo(BigDecimal.ZERO) <= 0) {
            return new BudgetCheckResult(true, currentCost, BigDecimal.ZERO, 0.0);
        }

        double percent = currentCost.divide(dailyLimit, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();

        double warnThreshold = aiProperties.getBudget().getWarningThresholdPercent();
        double cutoffThreshold = aiProperties.getBudget().getCutoffThresholdPercent();

        if (percent >= warnThreshold && warningLoggedToday.compareAndSet(false, true)) {
            log.warn("[AI BUDGET ALERT] Daily AI expenditure reached {}% of limit (${} / ${})",
                    String.format("%.1f", percent), currentCost, dailyLimit);
            if (meterRegistry != null) {
                meterRegistry.counter("smashhub.ai.budget.warning").increment();
            }
        }

        if (percent >= cutoffThreshold) {
            if (cutoffLoggedToday.compareAndSet(false, true)) {
                log.error("[AI BUDGET CUTOFF] Daily AI expenditure EXCEEDED limit 100%! Auto-disabling AI assistant: current=${}, limit=${}",
                        currentCost, dailyLimit);
                if (meterRegistry != null) {
                    meterRegistry.counter("smashhub.ai.budget.cutoff").increment();
                }
            }
            return new BudgetCheckResult(false, currentCost, dailyLimit, percent);
        }

        return new BudgetCheckResult(true, currentCost, dailyLimit, percent);
    }

    /**
     * Ghi nhận lượng token thực tế và cộng dồn chi phí ước tính theo ngày.
     */
    public void recordUsage(String modelName, long inputTokens, long outputTokens) {
        String today = getTodayKey();
        BigDecimal inCostPerM = aiProperties.getBudget().getInputCostPerMillion();
        BigDecimal outCostPerM = aiProperties.getBudget().getOutputCostPerMillion();

        BigDecimal inputCost = BigDecimal.valueOf(inputTokens)
                .multiply(inCostPerM)
                .divide(BigDecimal.valueOf(1_000_000), 8, RoundingMode.HALF_UP);

        BigDecimal outputCost = BigDecimal.valueOf(outputTokens)
                .multiply(outCostPerM)
                .divide(BigDecimal.valueOf(1_000_000), 8, RoundingMode.HALF_UP);

        BigDecimal totalCost = inputCost.add(outputCost);

        String costKey = KEY_DAILY_COST_PREFIX + today;
        String inTokenKey = KEY_DAILY_TOKENS_IN_PREFIX + today;
        String outTokenKey = KEY_DAILY_TOKENS_OUT_PREFIX + today;
        String reqKey = KEY_DAILY_REQUESTS_PREFIX + today;

        try {
            redisTemplate.opsForValue().increment(inTokenKey, inputTokens);
            redisTemplate.opsForValue().increment(outTokenKey, outputTokens);
            redisTemplate.opsForValue().increment(reqKey, 1);

            // Tăng chi phí trong Redis
            String currentCostStr = redisTemplate.opsForValue().get(costKey);
            BigDecimal newCost = (currentCostStr != null ? new BigDecimal(currentCostStr) : BigDecimal.ZERO).add(totalCost);
            redisTemplate.opsForValue().set(costKey, newCost.setScale(6, RoundingMode.HALF_UP).toPlainString());

            redisTemplate.expire(costKey, java.time.Duration.ofDays(7));
            redisTemplate.expire(inTokenKey, java.time.Duration.ofDays(7));
            redisTemplate.expire(outTokenKey, java.time.Duration.ofDays(7));
            redisTemplate.expire(reqKey, java.time.Duration.ofDays(7));
        } catch (Exception e) {
            log.error("Failed to update daily AI cost tracking in Redis", e);
        }

        // Báo cáo qua Micrometer metrics
        if (meterRegistry != null) {
            meterRegistry.counter("smashhub.ai.tokens.input", "model", modelName).increment(inputTokens);
            meterRegistry.counter("smashhub.ai.tokens.output", "model", modelName).increment(outputTokens);
        }
    }

    /**
     * Đồng bộ dữ liệu chi phí từ Redis vào DB bảng ai_cost_logs mỗi giờ và cuối ngày.
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void flushDailyCostToDatabase() {
        LocalDate today = LocalDate.now();
        String dateStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE);
        String costKey = KEY_DAILY_COST_PREFIX + dateStr;
        String inTokenKey = KEY_DAILY_TOKENS_IN_PREFIX + dateStr;
        String outTokenKey = KEY_DAILY_TOKENS_OUT_PREFIX + dateStr;
        String reqKey = KEY_DAILY_REQUESTS_PREFIX + dateStr;

        try {
            String costVal = redisTemplate.opsForValue().get(costKey);
            String inVal = redisTemplate.opsForValue().get(inTokenKey);
            String outVal = redisTemplate.opsForValue().get(outTokenKey);
            String reqVal = redisTemplate.opsForValue().get(reqKey);

            if (costVal != null || inVal != null) {
                BigDecimal cost = costVal != null ? new BigDecimal(costVal) : BigDecimal.ZERO;
                long inTokens = inVal != null ? Long.parseLong(inVal) : 0L;
                long outTokens = outVal != null ? Long.parseLong(outVal) : 0L;
                int requests = reqVal != null ? Integer.parseInt(reqVal) : 0;

                String model = aiProperties.getChatModel();
                AiCostLog costLog = aiCostLogRepository.findByLogDateAndModelName(today, model)
                        .orElseGet(() -> AiCostLog.builder()
                                .logDate(today)
                                .modelName(model)
                                .build());

                costLog.setInputTokens(inTokens);
                costLog.setOutputTokens(outTokens);
                costLog.setEstimatedCostUsd(cost);
                costLog.setRequestCount(requests);

                aiCostLogRepository.save(costLog);
            }
        } catch (Exception e) {
            log.error("Failed to flush AI cost logs to database", e);
        }
    }
}

