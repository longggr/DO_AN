package org.example.smashhub.chat.ai.config;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldDefaults;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "smashhub.ai")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AiProperties {

    boolean enabled = true;
    String apiKey = "";
    String baseUrl = "https://generativelanguage.googleapis.com";
    String chatModel = "gemini-1.5-flash";
    String embeddingModel = "text-embedding-004";

    Map<String, ModelLimit> limits = new HashMap<>();
    UserLimit userLimits = new UserLimit();
    Budget budget = new Budget();
    ExecutorConfig executor = new ExecutorConfig();
    RetryConfig retry = new RetryConfig();
    CircuitBreakerConfig circuitBreaker = new CircuitBreakerConfig();
    CacheConfig cache = new CacheConfig();
    TokenEstimation tokenEstimation = new TokenEstimation();
    RagConfig rag = new RagConfig();
    ToolsConfig tools = new ToolsConfig();
    SyncConfig sync = new SyncConfig();
    HandoffConfig handoff = new HandoffConfig();
    CopilotConfig copilot = new CopilotConfig();

    @Getter
    @Setter
    public static class ModelLimit {
        private int rpm = 12;          // Requests Per Minute (80% của 15)
        private long tpm = 800_000L;   // Tokens Per Minute (80% của 1,000,000)
        private int rpd = 1_200;       // Requests Per Day (80% của 1,500)
    }

    @Getter
    @Setter
    public static class UserLimit {
        private int rpm = 5;           // Tối đa 5 câu hỏi AI / phút / user
        private int rpd = 30;          // Tối đa 30 câu hỏi AI / ngày / user
    }

    @Getter
    @Setter
    public static class Budget {
        private BigDecimal dailyLimitUsd = new BigDecimal("2.00");
        private BigDecimal inputCostPerMillion = new BigDecimal("0.075");
        private BigDecimal outputCostPerMillion = new BigDecimal("0.300");
        private double warningThresholdPercent = 80.0;
        private double cutoffThresholdPercent = 100.0;
    }

    @Getter
    @Setter
    public static class ExecutorConfig {
        private int corePoolSize = 4;
        private int maxPoolSize = 8;
        private int queueCapacity = 50;
        private int keepAliveSeconds = 60;
    }

    @Getter
    @Setter
    public static class RetryConfig {
        private int maxAttempts = 2;
        private long backoffMillis = 1000L;
        private long maxBackoffMillis = 4000L;
        private double jitterFactor = 0.25;
    }

    @Getter
    @Setter
    public static class CircuitBreakerConfig {
        private int slidingWindowSize = 10;
        private float failureRateThreshold = 50.0f;
        private int waitDurationInOpenStateSeconds = 30;
        private int permittedNumberOfCallsInHalfOpenState = 3;
    }

    @Getter
    @Setter
    public static class CacheConfig {
        private long faqTtlSeconds = 86400L;        // 24 hours
        private long embeddingTtlSeconds = 604800L; // 7 days
    }

    @Getter
    @Setter
    public static class TokenEstimation {
        private double charsPerToken = 0.35; // Ước lượng ~1 ký tự = 0.35 token cho tiếng Việt
    }

    @Getter
    @Setter
    public static class RagConfig {
        private int maxContextProducts = 3;
        private int maxContextOrders = 2;
        private double similarityThreshold = 0.50;
        private int maxHistoryMessages = 6;
        private int maxRecommendedProducts = 3;
    }

    @Getter
    @Setter
    public static class ToolsConfig {
        private int maxIterations = 3;
        private int maxLlmCallsPerAnswer = 4;
        private long searchCacheTtlSeconds = 300L; // 5 minutes
    }

    @Getter
    @Setter
    public static class SyncConfig {
        private String productCron = "0 0 2 * * ?";
    }

    @Getter
    @Setter
    public static class HandoffConfig {
        private int consecutiveUncertainThreshold = 2;
        private long uncertainCacheTtlSeconds = 3600L;
    }

    @Getter
    @Setter
    public static class CopilotConfig {
        private int rateLimitRpm = 10;
        private int maxDraftVariants = 2;
    }

    public ModelLimit getModelLimit(String modelName) {
        if (limits != null && limits.containsKey(modelName)) {
            return limits.get(modelName);
        }
        // Fallback default an toàn
        ModelLimit fallback = new ModelLimit();
        if (modelName != null && modelName.contains("embedding")) {
            fallback.setRpm(1200);
            fallback.setTpm(800_000L);
            fallback.setRpd(10_000);
        }
        return fallback;
    }
}

