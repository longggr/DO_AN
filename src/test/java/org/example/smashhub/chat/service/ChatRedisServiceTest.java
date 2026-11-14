package org.example.smashhub.chat.service;

import org.example.smashhub.chat.config.ChatProperties;
import org.example.smashhub.chat.exception.ChatRateLimitExceededException;
import org.example.smashhub.chat.service.impl.ChatRedisServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatRedisServiceTest {

    @Mock
    RedisTemplate<String, String> redisTemplate;
    @Mock
    ChatProperties chatProperties;

    @InjectMocks
    ChatRedisServiceImpl chatRedisService;

    ChatProperties.RateLimit rateLimit;

    @BeforeEach
    void setUp() {
        rateLimit = new ChatProperties.RateLimit();
        rateLimit.setMaxMessages(15);
        rateLimit.setWindowSeconds(10);
        lenient().when(chatProperties.getRateLimit()).thenReturn(rateLimit);
    }

    @Test
    @DisplayName("Rate Limit biên 15: Tin thứ 15 được cho phép không ném ngoại lệ")
    void testRateLimitAllowsUpTo15Messages() {
        // Mock Lua script trả về current = 15, ttl = 6
        List<Object> redisResponse = Arrays.asList(15L, 6L);
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), eq("15"), eq("10")))
                .thenReturn(redisResponse);

        assertDoesNotThrow(() -> chatRedisService.checkRateLimit(123L));
    }

    @Test
    @DisplayName("Rate Limit biên 16: Tin thứ 16 bị chặn và ném ChatRateLimitExceededException")
    void testRateLimitBlocks16thMessage() {
        // Mock Lua script trả về current = 16, ttl = 8
        List<Object> redisResponse = Arrays.asList(16L, 8L);
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), eq("15"), eq("10")))
                .thenReturn(redisResponse);

        ChatRateLimitExceededException ex = assertThrows(
                ChatRateLimitExceededException.class,
                () -> chatRedisService.checkRateLimit(123L)
        );

        assertEquals(8, ex.getRemainingSeconds());
    }
}

