package org.example.smashhub.chat.dto.response;

import java.time.Instant;

public record ChatErrorResponse(
        String code,
        String message,
        long timestamp,
        String clientMessageId
) {
    public static ChatErrorResponse of(String code, String message, String clientMessageId) {
        return new ChatErrorResponse(code, message, Instant.now().toEpochMilli(), clientMessageId);
    }
}

