package org.example.smashhub.chat.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.example.smashhub.chat.config.ChatProperties;
import org.example.smashhub.chat.dto.event.RedisBroadcastMessage;
import org.example.smashhub.chat.service.ChatBroadcaster;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ChatBroadcasterImpl implements ChatBroadcaster {

    SimpMessagingTemplate messagingTemplate;
    RedisTemplate<String, String> redisTemplate;
    ChatProperties chatProperties;
    ObjectMapper objectMapper;

    @Override
    public void broadcast(String destination, Object payload) {
        if (chatProperties.getCluster().isEnableRedisPubSub()) {
            publishToRedis(destination, payload, null);
        } else {
            messagingTemplate.convertAndSend(destination, payload);
        }
    }

    @Override
    public void sendToUser(String username, String destination, Object payload) {
        if (chatProperties.getCluster().isEnableRedisPubSub()) {
            publishToRedis(destination, payload, username);
        } else {
            messagingTemplate.convertAndSendToUser(username, destination, payload);
        }
    }

    private void publishToRedis(String destination, Object payload, String targetUser) {
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            RedisBroadcastMessage message = new RedisBroadcastMessage(destination, payloadJson, targetUser);
            String messageJson = objectMapper.writeValueAsString(message);
            redisTemplate.convertAndSend(chatProperties.getCluster().getRedisChannel(), messageJson);
        } catch (Exception e) {
            log.error("Failed to publish chat message to Redis cluster channel, falling back to local broadcast: {}", e.getMessage());
            if (targetUser != null) {
                messagingTemplate.convertAndSendToUser(targetUser, destination, payload);
            } else {
                messagingTemplate.convertAndSend(destination, payload);
            }
        }
    }
}

