package org.example.smashhub.chat.service;

public interface ChatRedisService {

    void recordUserConnected(String sessionId, Long userId, String username, String role);

    void recordUserDisconnected(String sessionId);

    boolean isUserOnline(Long userId);

    boolean isAnyAdminOnline();

    void checkRateLimit(Long userId);

    boolean tryAcquireAutoReplyLock(Long conversationId);

    boolean tryAcquireTypingThrottle(Long userId, Long conversationId);

    void checkAttachmentUploadRateLimit(Long userId);

    void cacheConversationPermission(Long userId, Long conversationId, boolean allowed);

    Boolean getCachedConversationPermission(Long userId, Long conversationId);
}

