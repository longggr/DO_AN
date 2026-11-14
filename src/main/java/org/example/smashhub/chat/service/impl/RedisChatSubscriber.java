package org.example.smashhub.chat.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.example.smashhub.chat.config.ChatProperties;
import org.example.smashhub.chat.dto.event.RedisBroadcastMessage;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class RedisChatSubscriber implements MessageListener {

    SimpMessagingTemplate messagingTemplate;
    RedisMessageListenerContainer listenerContainer;
    ChatProperties chatProperties;
    ObjectMapper objectMapper;

    @PostConstruct
    public void init() {
        if (chatProperties.getCluster().isEnableRedisPubSub()) {
            String channel = chatProperties.getCluster().getRedisChannel();
            listenerContainer.addMessageListener(this, new ChannelTopic(channel));
            log.info("Redis Pub/Sub Chat Cluster Subscriber registered on channel: {}", channel);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            RedisBroadcastMessage broadcastMsg = objectMapper.readValue(body, RedisBroadcastMessage.class);

            Object parsedPayload = objectMapper.readValue(broadcastMsg.payloadJson(), Object.class);

            if (broadcastMsg.targetUser() != null && !broadcastMsg.targetUser().isBlank()) {
                messagingTemplate.convertAndSendToUser(broadcastMsg.targetUser(), broadcastMsg.destination(), parsedPayload);
            } else {
                messagingTemplate.convertAndSend(broadcastMsg.destination(), parsedPayload);
            }
        } catch (Exception e) {
            log.error("Failed to process Redis Pub/Sub cluster broadcast message", e);
        }
    }
}

