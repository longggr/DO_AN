package org.example.smashhub.chat.dto.event;

import org.example.smashhub.chat.enums.SenderRole;

import java.time.LocalDateTime;

public record ReadEvent(
        Long conversationId,
        SenderRole readerRole,
        LocalDateTime readAt
) {}

