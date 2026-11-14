package org.example.smashhub.chat.dto.event;

public record RedisBroadcastMessage(
        String destination,
        String payloadJson,
        String targetUser
) {}

