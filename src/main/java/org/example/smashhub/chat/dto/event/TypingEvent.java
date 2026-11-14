package org.example.smashhub.chat.dto.event;

import org.example.smashhub.chat.enums.SenderRole;

public record TypingEvent(
        Long conversationId,
        Long userId,
        String username,
        SenderRole senderRole,
        boolean isTyping
) {}

