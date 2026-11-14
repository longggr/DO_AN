package org.example.smashhub.chat.ai.limiter;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.example.smashhub.chat.ai.config.AiProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Kill switch động cho toàn bộ hệ thống Trợ lý AI.
 * Ưu tiên giá trị ghi đè trong Redis (không cần khởi động lại ứng dụng).
 * Nếu Redis không thiết lập thì lấy theo cấu hình smashhub.ai.enabled trong application.yaml.
 */
@Component
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AiKillSwitch {

    static String REDIS_KILL_SWITCH_KEY = "smashhub:ai:killswitch";

    StringRedisTemplate redisTemplate;
    AiProperties aiProperties;

    /**
     * Kiểm tra xem AI Assistant có đang được phép hoạt động hay không.
     */
    public boolean isAiEnabled() {
        try {
            String redisVal = redisTemplate.opsForValue().get(REDIS_KILL_SWITCH_KEY);
            if ("DISABLED".equalsIgnoreCase(redisVal) || "FALSE".equalsIgnoreCase(redisVal)) {
                return false;
            }
            if ("ENABLED".equalsIgnoreCase(redisVal) || "TRUE".equalsIgnoreCase(redisVal)) {
                return true;
            }
        } catch (Exception e) {
            log.warn("Failed to check AI kill switch from Redis, falling back to local properties", e);
        }
        return aiProperties.isEnabled();
    }

    /**
     * Bật hoặc tắt tức thời toàn bộ tính năng AI qua Redis.
     */
    public void setAiEnabled(boolean enabled) {
        try {
            redisTemplate.opsForValue().set(REDIS_KILL_SWITCH_KEY, enabled ? "ENABLED" : "DISABLED");
            log.info("[AI KILL SWITCH] AI Assistant status updated dynamically to: {}", enabled ? "ENABLED" : "DISABLED");
        } catch (Exception e) {
            log.error("Failed to update AI kill switch in Redis", e);
        }
    }
}

