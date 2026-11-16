package org.example.smashhub.chat.dto.event;

import org.example.smashhub.chat.enums.ConversationStatus;

import java.time.LocalDateTime;

public record ConversationUpdateEvent(
        Long conversationId,
        Long customerId,
        String customerUsername,
        ConversationStatus status,
        org.example.smashhub.chat.enums.HandlerMode handlerMode,
        String handoffReason,
        String lastMessagePreview,
        LocalDateTime lastMessageAt,
        int customerUnreadCount,
        int adminUnreadCount,
        Long assignedAdminId,
        String assignedAdminUsername
) {}

