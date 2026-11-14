package org.example.smashhub.chat.service.impl;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.example.smashhub.chat.config.ChatProperties;
import org.example.smashhub.chat.exception.ChatRateLimitExceededException;
import org.example.smashhub.chat.service.ChatRedisService;
import org.example.smashhub.exception.AppException;
import org.example.smashhub.exception.ErrorCode;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Service
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ChatRedisServiceImpl implements ChatRedisService {

    static String KEY_ONLINE_USERS = "smashhub:chat:online_users";
    static String KEY_ONLINE_ADMINS = "smashhub:chat:online_admins";
    static String PREFIX_USER_SESSIONS = "smashhub:chat:user_sessions:";
    static String PREFIX_SESSION = "smashhub:chat:session:";
    static String PREFIX_RATE_LIMIT = "smashhub:chat:ratelimit:";
    static String PREFIX_AUTO_REPLY = "smashhub:chat:autoreply:";
    static String PREFIX_TYPING = "smashhub:chat:typing:";
    static String PREFIX_PERM = "smashhub:chat:perm:";
    static String PREFIX_UPLOAD_LIMIT = "smashhub:chat:uploadlimit:";

    static String RATE_LIMIT_LUA =
            "local key = KEYS[1]\n" +
            "local limit = tonumber(ARGV[1])\n" +
            "local window = tonumber(ARGV[2])\n" +
            "local current = redis.call('INCR', key)\n" +
            "if current == 1 then\n" +
            "    redis.call('EXPIRE', key, window)\n" +
            "end\n" +
            "local ttl = redis.call('TTL', key)\n" +
            "return {current, ttl}";

    static DefaultRedisScript<List> RATE_LIMIT_SCRIPT = new DefaultRedisScript<>(RATE_LIMIT_LUA, List.class);

    RedisTemplate<String, String> redisTemplate;
    ChatProperties chatProperties;

    // Fallback in-memory rate limiter nếu Redis gặp sự cố
    ConcurrentHashMap<Long, ConcurrentLinkedQueue<Long>> localRateLimits = new ConcurrentHashMap<>();

    @Override
    public void recordUserConnected(String sessionId, Long userId, String username, String role) {
        if (sessionId == null || userId == null) return;
        try {
            String sessionKey = PREFIX_SESSION + sessionId;
            String userSessionKey = PREFIX_USER_SESSIONS + userId;
            String sessionData = userId + ":" + (role != null ? role : "CUSTOMER");

            redisTemplate.opsForValue().set(sessionKey, sessionData, Duration.ofMinutes(10));
            redisTemplate.opsForSet().add(userSessionKey, sessionId);
            redisTemplate.expire(userSessionKey, Duration.ofHours(24));

            redisTemplate.opsForSet().add(KEY_ONLINE_USERS, String.valueOf(userId));
            if ("ADMIN".equalsIgnoreCase(role) || "STAFF".equalsIgnoreCase(role)) {
                redisTemplate.opsForSet().add(KEY_ONLINE_ADMINS, String.valueOf(userId));
            }
            log.info("User connected WebSocket: userId={}, username={}, role={}, sessionId={}", userId, username, role, sessionId);
        } catch (Exception e) {
            log.warn("Fail-open: Redis error while recording user connect [userId={}, sessionId={}]: {}", userId, sessionId, e.getMessage());
        }
    }

    @Override
    public void recordUserDisconnected(String sessionId) {
        if (sessionId == null) return;
        try {
            String sessionKey = PREFIX_SESSION + sessionId;
            String sessionData = redisTemplate.opsForValue().get(sessionKey);
            redisTemplate.delete(sessionKey);

            if (sessionData != null && sessionData.contains(":")) {
                String[] parts = sessionData.split(":", 2);
                String userIdStr = parts[0];
                String role = parts[1];
                String userSessionKey = PREFIX_USER_SESSIONS + userIdStr;

                redisTemplate.opsForSet().remove(userSessionKey, sessionId);
                Long remainingSessions = redisTemplate.opsForSet().size(userSessionKey);

                if (remainingSessions == null || remainingSessions <= 0) {
                    redisTemplate.delete(userSessionKey);
                    redisTemplate.opsForSet().remove(KEY_ONLINE_USERS, userIdStr);
                    if ("ADMIN".equalsIgnoreCase(role) || "STAFF".equalsIgnoreCase(role)) {
                        redisTemplate.opsForSet().remove(KEY_ONLINE_ADMINS, userIdStr);
                    }
                    log.info("User fully offline WebSocket: userId={}, role={}", userIdStr, role);
                } else {
                    log.debug("Session closed for user: userId={}, remainingSessions={}", userIdStr, remainingSessions);
                }
            }
        } catch (Exception e) {
            log.warn("Fail-open: Redis error while recording user disconnect [sessionId={}]: {}", sessionId, e.getMessage());
        }
    }

    @Override
    public boolean isUserOnline(Long userId) {
        if (userId == null) return false;
        try {
            Boolean isMember = redisTemplate.opsForSet().isMember(KEY_ONLINE_USERS, String.valueOf(userId));
            return Boolean.TRUE.equals(isMember);
        } catch (Exception e) {
            log.warn("Fail-open: Redis error in isUserOnline [userId={}]: {}", userId, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean isAnyAdminOnline() {
        try {
            Long size = redisTemplate.opsForSet().size(KEY_ONLINE_ADMINS);
            return size != null && size > 0;
        } catch (Exception e) {
            log.warn("Fail-open: Redis error in isAnyAdminOnline: {}", e.getMessage());
            // Fail-open: giả định có admin để tránh ném lỗi hay gửi auto-reply sai khi Redis tạm gián đoạn
            return true;
        }
    }

    @Override
    public void checkRateLimit(Long userId) {
        if (userId == null) return;
        int maxMessages = chatProperties.getRateLimit().getMaxMessages();
        int windowSeconds = chatProperties.getRateLimit().getWindowSeconds();
        String key = PREFIX_RATE_LIMIT + userId;

        try {
            List<Object> result = redisTemplate.execute(
                    RATE_LIMIT_SCRIPT,
                    Collections.singletonList(key),
                    String.valueOf(maxMessages),
                    String.valueOf(windowSeconds)
            );

            if (result != null && result.size() >= 2) {
                long currentCount = ((Number) result.get(0)).longValue();
                long ttl = ((Number) result.get(1)).longValue();
                if (currentCount > maxMessages) {
                    long remainingSeconds = ttl > 0 ? ttl : windowSeconds;
                    log.warn("Rate limit exceeded for userId={}: count={}/{}, remainingSeconds={}",
                            userId, currentCount, maxMessages, remainingSeconds);
                    throw new ChatRateLimitExceededException(remainingSeconds);
                }
            }
        } catch (ChatRateLimitExceededException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Redis rate limit failure. Falling back to local in-memory rate limiter: {}", e.getMessage());
            checkLocalRateLimit(userId, maxMessages, windowSeconds);
        }
    }

    private void checkLocalRateLimit(Long userId, int maxMessages, int windowSeconds) {
        long now = System.currentTimeMillis();
        long windowMillis = windowSeconds * 1000L;
        ConcurrentLinkedQueue<Long> timestamps = localRateLimits.computeIfAbsent(userId, k -> new ConcurrentLinkedQueue<>());

        // Dọn dẹp timestamps ngoài cửa sổ
        while (!timestamps.isEmpty() && now - timestamps.peek() > windowMillis) {
            timestamps.poll();
        }

        if (timestamps.size() >= maxMessages) {
            long oldest = timestamps.peek() != null ? timestamps.peek() : now;
            long remainingSeconds = Math.max(1, (windowMillis - (now - oldest)) / 1000L);
            throw new ChatRateLimitExceededException(remainingSeconds);
        }
        timestamps.add(now);
    }

    @Override
    public boolean tryAcquireAutoReplyLock(Long conversationId) {
        if (conversationId == null) return false;
        try {
            String key = PREFIX_AUTO_REPLY + conversationId;
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, "1", Duration.ofMinutes(30));
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            log.warn("Fail-open: Redis error in tryAcquireAutoReplyLock [conversationId={}]: {}", conversationId, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean tryAcquireTypingThrottle(Long userId, Long conversationId) {
        if (userId == null || conversationId == null) return false;
        try {
            String key = PREFIX_TYPING + userId + ":" + conversationId;
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(1));
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            log.warn("Fail-open: Redis error in tryAcquireTypingThrottle: {}", e.getMessage());
            return true;
        }
    }

    @Override
    public void checkAttachmentUploadRateLimit(Long userId) {
        if (userId == null) return;
        int maxUploads = chatProperties.getAttachment().getMaxUploadsPerMinute();
        String key = PREFIX_UPLOAD_LIMIT + userId;
        try {
            List<Object> result = redisTemplate.execute(
                    RATE_LIMIT_SCRIPT,
                    Collections.singletonList(key),
                    String.valueOf(maxUploads),
                    "60"
            );
            if (result != null && result.size() >= 2) {
                long currentCount = ((Number) result.get(0)).longValue();
                if (currentCount > maxUploads) {
                    throw new AppException(ErrorCode.CHAT_ATTACHMENT_RATE_LIMITED);
                }
            }
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Fail-open: Redis error in checkAttachmentUploadRateLimit: {}", e.getMessage());
        }
    }

    @Override
    public void cacheConversationPermission(Long userId, Long conversationId, boolean allowed) {
        if (userId == null || conversationId == null) return;
        try {
            String key = PREFIX_PERM + userId + ":" + conversationId;
            redisTemplate.opsForValue().set(key, allowed ? "1" : "0", Duration.ofSeconds(60));
        } catch (Exception e) {
            log.warn("Fail-open: Redis error caching permission: {}", e.getMessage());
        }
    }

    @Override
    public Boolean getCachedConversationPermission(Long userId, Long conversationId) {
        if (userId == null || conversationId == null) return null;
        try {
            String key = PREFIX_PERM + userId + ":" + conversationId;
            String val = redisTemplate.opsForValue().get(key);
            if ("1".equals(val)) return true;
            if ("0".equals(val)) return false;
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}

