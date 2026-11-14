package org.example.smashhub.chat.ai.limiter;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.example.smashhub.chat.ai.config.AiProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * GeminiRateLimiter: Quản lý giới hạn Quota Gemini phân tán qua Redis + Lua scripts.
 * - Giới hạn toàn cục theo Model: RPM, TPM, RPD (Reset đúng 00:00 America/Los_Angeles có tính DST).
 * - Giới hạn cá nhân theo User: N lượt/phút, M lượt/ngày.
 * - Giới hạn riêng cho Embedding.
 */
@Component
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class GeminiRateLimiter {

    public static final ZoneId PACIFIC_ZONE = ZoneId.of("America/Los_Angeles");

    // Lua script kiểm tra đồng thời RPM và RPD của từng User
    static final String USER_RATE_LIMIT_LUA = """
            local rpmKey = KEYS[1]
            local rpdKey = KEYS[2]
            local maxRpm = tonumber(ARGV[1])
            local maxRpd = tonumber(ARGV[2])
            local rpdTtl = tonumber(ARGV[3])

            local currentRpm = redis.call('INCR', rpmKey)
            if currentRpm == 1 then
                redis.call('EXPIRE', rpmKey, 60)
            end
            if currentRpm > maxRpm then
                return 'USER_RPM_EXCEEDED'
            end

            local currentRpd = redis.call('INCR', rpdKey)
            if currentRpd == 1 then
                redis.call('EXPIRE', rpdKey, rpdTtl)
            end
            if currentRpd > maxRpd then
                return 'USER_RPD_EXCEEDED'
            end

            return 'ALLOWED'
            """;

    // Lua script kiểm tra đồng thời RPM, TPM và RPD toàn cục của Model
    static final String MODEL_RATE_LIMIT_LUA = """
            local rpmKey = KEYS[1]
            local tpmKey = KEYS[2]
            local rpdKey = KEYS[3]

            local maxRpm = tonumber(ARGV[1])
            local maxTpm = tonumber(ARGV[2])
            local maxRpd = tonumber(ARGV[3])
            local estimatedTokens = tonumber(ARGV[4])
            local rpdTtl = tonumber(ARGV[5])

            local curRpm = redis.call('INCR', rpmKey)
            if curRpm == 1 then
                redis.call('EXPIRE', rpmKey, 60)
            end
            if curRpm > maxRpm then
                return 'MODEL_RPM_EXCEEDED'
            end

            local curTpm = redis.call('INCRBY', tpmKey, estimatedTokens)
            if curTpm == estimatedTokens then
                redis.call('EXPIRE', tpmKey, 60)
            end
            if curTpm > maxTpm then
                return 'MODEL_TPM_EXCEEDED'
            end

            local curRpd = redis.call('INCR', rpdKey)
            if curRpd == 1 then
                redis.call('EXPIRE', rpdKey, rpdTtl)
            end
            if curRpd > maxRpd then
                return 'MODEL_RPD_EXCEEDED'
            end

            return 'ALLOWED'
            """;

    public static final DefaultRedisScript<String> USER_LIMIT_SCRIPT =
            new DefaultRedisScript<>(USER_RATE_LIMIT_LUA, String.class);
    public static final DefaultRedisScript<String> MODEL_LIMIT_SCRIPT =
            new DefaultRedisScript<>(MODEL_RATE_LIMIT_LUA, String.class);

    StringRedisTemplate redisTemplate;
    AiProperties aiProperties;

    @Getter
    public static class RateLimitResult {
        private final boolean allowed;
        private final String reason;
        private final long estimatedTokens;

        public RateLimitResult(boolean allowed, String reason, long estimatedTokens) {
            this.allowed = allowed;
            this.reason = reason;
            this.estimatedTokens = estimatedTokens;
        }

        public static RateLimitResult ok(long tokens) {
            return new RateLimitResult(true, "OK", tokens);
        }

        public static RateLimitResult denied(String reason) {
            return new RateLimitResult(false, reason, 0L);
        }
    }

    /**
     * Tính toán số giây còn lại cho đến 00:00 kế tiếp theo múi giờ Pacific (America/Los_Angeles).
     * Xử lý chính xác cả khi chuyển đổi Daylight Saving Time (DST).
     */
    public long getSecondsUntilNextPacificMidnight() {
        ZonedDateTime nowPacific = ZonedDateTime.now(PACIFIC_ZONE);
        ZonedDateTime nextMidnightPacific = nowPacific.toLocalDate().plusDays(1).atStartOfDay(PACIFIC_ZONE);
        long seconds = Duration.between(nowPacific, nextMidnightPacific).getSeconds();
        return Math.max(60L, seconds);
    }

    public String getCurrentPacificDateStr() {
        return ZonedDateTime.now(PACIFIC_ZONE).toLocalDate().toString();
    }

    /**
     * Ước lượng số token đầu vào dựa trên số ký tự (cấu hình charsPerToken).
     */
    public long estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 1L;
        }
        double factor = aiProperties.getTokenEstimation().getCharsPerToken();
        if (factor <= 0) factor = 0.35;
        return Math.max(1L, (long) Math.ceil(text.length() * factor));
    }

    /**
     * Kiểm tra và cấp phép gọi Gemini Chat (đồng thời User + Model Quota).
     */
    public RateLimitResult tryAcquireChat(String modelName, Long userId, String promptText) {
        long estimatedTokens = estimateTokens(promptText);
        long rpdTtl = getSecondsUntilNextPacificMidnight();
        String pacificDate = getCurrentPacificDateStr();

        // 1. Kiểm tra giới hạn User
        if (userId != null && userId > 0) {
            String userRpmKey = "smashhub:ai:ratelimit:user:" + userId + ":rpm";
            String userRpdKey = "smashhub:ai:ratelimit:user:" + userId + ":rpd:" + pacificDate;
            int maxUserRpm = aiProperties.getUserLimits().getRpm();
            int maxUserRpd = aiProperties.getUserLimits().getRpd();

            try {
                String userResult = redisTemplate.execute(
                        USER_LIMIT_SCRIPT,
                        Arrays.asList(userRpmKey, userRpdKey),
                        String.valueOf(maxUserRpm),
                        String.valueOf(maxUserRpd),
                        String.valueOf(rpdTtl)
                );

                if (!"ALLOWED".equals(userResult)) {
                    log.warn("Gemini AI user rate limit hit for userId={}: {}", userId, userResult);
                    return RateLimitResult.denied(userResult);
                }
            } catch (Exception e) {
                log.error("Redis error executing user rate limit script, allowing request as fallback", e);
            }
        }

        // 2. Kiểm tra giới hạn toàn cục theo Model
        AiProperties.ModelLimit modelLimit = aiProperties.getModelLimit(modelName);
        long minuteEpoch = System.currentTimeMillis() / 60000;
        String modelRpmKey = "smashhub:ai:limit:" + modelName + ":rpm:" + minuteEpoch;
        String modelTpmKey = "smashhub:ai:limit:" + modelName + ":tpm:" + minuteEpoch;
        String modelRpdKey = "smashhub:ai:limit:" + modelName + ":rpd:" + pacificDate;

        try {
            String modelResult = redisTemplate.execute(
                    MODEL_LIMIT_SCRIPT,
                    Arrays.asList(modelRpmKey, modelTpmKey, modelRpdKey),
                    String.valueOf(modelLimit.getRpm()),
                    String.valueOf(modelLimit.getTpm()),
                    String.valueOf(modelLimit.getRpd()),
                    String.valueOf(estimatedTokens),
                    String.valueOf(rpdTtl)
            );

            if (modelResult != null && !"ALLOWED".equals(modelResult)) {
                log.warn("Gemini AI project model quota hit for model={}: {}", modelName, modelResult);
                return RateLimitResult.denied(modelResult);
            }
        } catch (Exception e) {
            log.error("Redis error executing model rate limit script, allowing request as fallback", e);
        }

        return RateLimitResult.ok(estimatedTokens);
    }

    /**
     * Kiểm tra giới hạn cho Embedding Model (Text-Embedding-004).
     */
    public RateLimitResult tryAcquireEmbedding(String modelName, int textCount, long estimatedTokens) {
        long rpdTtl = getSecondsUntilNextPacificMidnight();
        String pacificDate = getCurrentPacificDateStr();
        AiProperties.ModelLimit modelLimit = aiProperties.getModelLimit(modelName);

        long minuteEpoch = System.currentTimeMillis() / 60000;
        String modelRpmKey = "smashhub:ai:limit:" + modelName + ":rpm:" + minuteEpoch;
        String modelTpmKey = "smashhub:ai:limit:" + modelName + ":tpm:" + minuteEpoch;
        String modelRpdKey = "smashhub:ai:limit:" + modelName + ":rpd:" + pacificDate;

        try {
            String modelResult = redisTemplate.execute(
                    MODEL_LIMIT_SCRIPT,
                    Arrays.asList(modelRpmKey, modelTpmKey, modelRpdKey),
                    String.valueOf(modelLimit.getRpm()),
                    String.valueOf(modelLimit.getTpm()),
                    String.valueOf(modelLimit.getRpd()),
                    String.valueOf(estimatedTokens),
                    String.valueOf(rpdTtl)
            );

            if (!"ALLOWED".equals(modelResult)) {
                log.warn("Gemini Embedding quota exceeded for model={}: {}", modelName, modelResult);
                return RateLimitResult.denied(modelResult);
            }
        } catch (Exception e) {
            log.error("Redis error checking embedding rate limit", e);
        }

        return RateLimitResult.ok(estimatedTokens);
    }

    /**
     * Đối soát (reconciliation) lượng token thực tế trả về từ response của Gemini với lượng token đã ước tính.
     */
    public void reconcileTokens(String modelName, long estimatedTokens, long actualPromptTokens, long actualCandidatesTokens) {
        long totalActualTokens = actualPromptTokens + actualCandidatesTokens;
        long delta = totalActualTokens - estimatedTokens;
        if (delta == 0) return;

        long minuteEpoch = System.currentTimeMillis() / 60000;
        String modelTpmKey = "smashhub:ai:limit:" + modelName + ":tpm:" + minuteEpoch;

        try {
            if (delta > 0) {
                redisTemplate.opsForValue().increment(modelTpmKey, delta);
            } else {
                redisTemplate.opsForValue().decrement(modelTpmKey, Math.abs(delta));
            }
        } catch (Exception e) {
            log.warn("Failed to reconcile TPM delta in Redis: delta={}", delta, e);
        }
    }
}

