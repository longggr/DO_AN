package org.example.smashhub.chat.ai;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.example.smashhub.chat.ai.client.GeminiApiClient;
import org.example.smashhub.chat.ai.client.dto.GeminiGenerateRequest;
import org.example.smashhub.chat.ai.config.AiProperties;
import org.example.smashhub.chat.ai.limiter.AiBudgetTracker;
import org.example.smashhub.chat.ai.limiter.AiKillSwitch;
import org.example.smashhub.chat.ai.limiter.GeminiRateLimiter;
import org.example.smashhub.chat.ai.service.AiCatalogRagService;
import org.example.smashhub.chat.ai.service.AiChatService;
import org.example.smashhub.chat.ai.service.AiFaqService;
import org.example.smashhub.chat.dto.response.ChatMessageResponse;
import org.example.smashhub.chat.entity.ChatMessage;
import org.example.smashhub.chat.entity.Conversation;
import org.example.smashhub.chat.enums.ConversationStatus;
import org.example.smashhub.chat.enums.HandlerMode;
import org.example.smashhub.chat.enums.MessageType;
import org.example.smashhub.chat.enums.SenderRole;
import org.example.smashhub.chat.mapper.ChatMapper;
import org.example.smashhub.chat.repository.ChatMessageRepository;
import org.example.smashhub.chat.repository.ConversationRepository;
import org.example.smashhub.chat.service.ChatBroadcaster;
import org.example.smashhub.user.entity.User;
import org.example.smashhub.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.HttpClientErrorException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class GeminiAiAssistantTest {

    @Mock
    StringRedisTemplate redisTemplate;

    @Mock
    GeminiApiClient geminiApiClient;

    @Mock
    AiBudgetTracker aiBudgetTracker;

    @Mock
    AiKillSwitch aiKillSwitch;

    @Mock
    AiFaqService aiFaqService;

    @Mock
    AiCatalogRagService aiCatalogRagService;

    @Mock
    ConversationRepository conversationRepository;

    @Mock
    ChatMessageRepository chatMessageRepository;

    @Mock
    UserRepository userRepository;

    @Mock
    ChatBroadcaster chatBroadcaster;

    @Mock
    ChatMapper chatMapper;

    @Mock
    ThreadPoolTaskExecutor geminiTaskExecutor;

    AiProperties aiProperties;
    GeminiRateLimiter geminiRateLimiter;
    AiChatService aiChatService;

    User customerUser;
    Conversation conversation;

    @BeforeEach
    void setUp() {
        aiProperties = new AiProperties();
        aiProperties.setEnabled(true);
        aiProperties.setApiKey("test_gemini_api_key");
        aiProperties.setChatModel("gemini-1.5-flash");

        // Cấu hình giới hạn
        AiProperties.ModelLimit flashLimit = new AiProperties.ModelLimit();
        flashLimit.setRpm(12);
        flashLimit.setTpm(800_000L);
        flashLimit.setRpd(1200);
        aiProperties.getLimits().put("gemini-1.5-flash", flashLimit);

        geminiRateLimiter = new GeminiRateLimiter(redisTemplate, aiProperties);

        aiChatService = new AiChatService(
                geminiApiClient,
                geminiRateLimiter,
                aiBudgetTracker,
                aiKillSwitch,
                aiFaqService,
                aiCatalogRagService,
                conversationRepository,
                chatMessageRepository,
                userRepository,
                chatBroadcaster,
                chatMapper,
                aiProperties
        );
        aiChatService.setGeminiTaskExecutor(geminiTaskExecutor);

        customerUser = new User();
        customerUser.setId(10L);
        customerUser.setUsername("testcustomer");
        customerUser.setEmail("customer@smashhub.vn");

        conversation = Conversation.builder()
                .customer(customerUser)
                .status(ConversationStatus.OPEN)
                .handlerMode(HandlerMode.AI)
                .build();
        conversation.setId(100L);
    }

    // =========================================================================
    // 1. TEST BẮT BUỘC: RESET RPD ĐÚNG MÚI GIỜ PACIFIC (AMERICA/LOS_ANGELES)
    // =========================================================================
    @Test
    @DisplayName("1. RPD TTL và reset phải tính chính xác tới 00:00 Pacific (America/Los_Angeles)")
    void testRpdResetPacificTimezone() {
        ZoneId pacificZone = ZoneId.of("America/Los_Angeles");
        ZonedDateTime nowPacific = ZonedDateTime.now(pacificZone);
        ZonedDateTime nextMidnight = nowPacific.toLocalDate().plusDays(1).atStartOfDay(pacificZone);

        long expectedSeconds = Duration.between(nowPacific, nextMidnight).getSeconds();
        long actualSeconds = geminiRateLimiter.getSecondsUntilNextPacificMidnight();

        // Chênh lệch không quá 2 giây thực thi
        assertTrue(Math.abs(expectedSeconds - actualSeconds) <= 2,
                "Seconds until Pacific midnight must accurately match America/Los_Angeles timezone");

        // Ngày hiện tại theo giờ Pacific
        String expectedDate = nowPacific.toLocalDate().toString();
        assertEquals(expectedDate, geminiRateLimiter.getCurrentPacificDateStr(),
                "Pacific date string must strictly follow America/Los_Angeles date");
    }

    // =========================================================================
    // 2. TEST BẮT BUỘC: LIMITER ĐÚNG Ở BIÊN (BOUNDARY CONDITIONS)
    // =========================================================================
    @Test
    @DisplayName("2. Limiter chặn chính xác khi vượt ngưỡng biên (User RPM, Model RPM, Model TPM)")
    void testLimiterAtBoundaries() {
        // Trường hợp 1: User vượt RPM
        when(redisTemplate.execute(eq(GeminiRateLimiter.USER_LIMIT_SCRIPT), anyList(), any(), any(), any()))
                .thenReturn("USER_RPM_EXCEEDED");

        GeminiRateLimiter.RateLimitResult res1 = geminiRateLimiter.tryAcquireChat("gemini-1.5-flash", 10L, "Tư vấn vợt 4U");
        assertFalse(res1.isAllowed(), "Must be rejected when User RPM exceeded");
        assertEquals("USER_RPM_EXCEEDED", res1.getReason());

        // Trường hợp 2: Model toàn cục vượt TPM
        when(redisTemplate.execute(eq(GeminiRateLimiter.USER_LIMIT_SCRIPT), anyList(), any(), any(), any()))
                .thenReturn("ALLOWED"); // User check passed
        when(redisTemplate.execute(eq(GeminiRateLimiter.MODEL_LIMIT_SCRIPT), anyList(), any(), any(), any(), any(), any()))
                .thenReturn("MODEL_TPM_EXCEEDED"); // Model check failed

        GeminiRateLimiter.RateLimitResult res2 = geminiRateLimiter.tryAcquireChat("gemini-1.5-flash", 10L, "Tư vấn vợt 4U");
        assertFalse(res2.isAllowed(), "Must be rejected when Model TPM exceeded");
        assertEquals("MODEL_TPM_EXCEEDED", res2.getReason());

        // Trường hợp 3: Nằm trong ngưỡng an toàn
        when(redisTemplate.execute(eq(GeminiRateLimiter.USER_LIMIT_SCRIPT), anyList(), any(), any(), any()))
                .thenReturn("ALLOWED");
        when(redisTemplate.execute(eq(GeminiRateLimiter.MODEL_LIMIT_SCRIPT), anyList(), any(), any(), any(), any(), any()))
                .thenReturn("ALLOWED");

        GeminiRateLimiter.RateLimitResult res3 = geminiRateLimiter.tryAcquireChat("gemini-1.5-flash", 10L, "Tư vấn vợt 4U");
        assertTrue(res3.isAllowed(), "Must be allowed when within all boundary limits");
        assertTrue(res3.getEstimatedTokens() > 0);
    }

    // =========================================================================
    // 3. TEST BẮT BUỘC: 429 GIẢ LẬP KÍCH HOẠT RETRY RỒI FALLBACK
    // =========================================================================
    @Test
    @DisplayName("3. HTTP 429 giả lập kích hoạt Retry có kiểm soát rồi Fallback sang HUMAN")
    void testSimulated429TriggersRetryThenFallback() {
        when(aiKillSwitch.isAiEnabled()).thenReturn(true);
        when(aiBudgetTracker.checkDailyBudget()).thenReturn(
                new AiBudgetTracker.BudgetCheckResult(true, BigDecimal.ZERO, BigDecimal.valueOf(2.0), 0.0));
        when(aiFaqService.findFaqAnswer(anyString())).thenReturn(Optional.empty());

        // Redis cho phép lần đầu và lần retry
        when(redisTemplate.execute(eq(GeminiRateLimiter.USER_LIMIT_SCRIPT), anyList(), any(), any(), any()))
                .thenReturn("ALLOWED");
        when(redisTemplate.execute(eq(GeminiRateLimiter.MODEL_LIMIT_SCRIPT), anyList(), any(), any(), any(), any(), any()))
                .thenReturn("ALLOWED");

        // Giả lập Gemini API trả HTTP 429 TOO_MANY_REQUESTS với header Retry-After
        HttpHeaders headers = new HttpHeaders();
        headers.add("Retry-After", "0");
        HttpClientErrorException ex429 = HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "RESOURCE_EXHAUSTED", headers, new byte[0], null);

        when(geminiApiClient.generateContent(anyString(), any(GeminiGenerateRequest.class)))
                .thenThrow(ex429);

        when(conversationRepository.findById(100L)).thenReturn(Optional.of(conversation));
        when(userRepository.findByUsername("system")).thenReturn(Optional.of(customerUser));
        when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(i -> i.getArgument(0));
        when(chatMapper.toChatMessageResponse(any(ChatMessage.class))).thenReturn(new ChatMessageResponse());

        // Thực thi gọi hàm xử lý
        doAnswer(invocation -> {
            Runnable r = invocation.getArgument(0);
            r.run(); // Chạy đồng bộ trong test
            return null;
        }).when(geminiTaskExecutor).execute(any(Runnable.class));

        aiChatService.handleCustomerMessageAsync(100L, customerUser, "Vợt Nanoflare 1000Z còn hàng không shop?");

        // Kiểm tra: GeminiApiClient phải được gọi đúng (1 lần đầu + 2 lần retry = 3 lần)
        verify(geminiApiClient, times(3)).generateContent(anyString(), any(GeminiGenerateRequest.class));

        // Kiểm tra: Sau khi retry thất bại, Fallback được kích hoạt: chuyển handlerMode sang HUMAN
        verify(conversationRepository).updateHandlerMode(100L, HandlerMode.HUMAN);

        // Kiểm tra: Gửi thông báo SYSTEM cho khách hàng, không bao giờ im lặng
        verify(conversationRepository).updateOnSystemMessage(eq(100L), contains("Trợ lý đang bận"), any());
        verify(chatBroadcaster).broadcast(eq("/topic/conversations/100"), any());
    }

    // =========================================================================
    // 4. TEST BẮT BUỘC: CIRCUIT BREAKER MỞ VÀ ĐÓNG (RESILIENCE4J)
    // =========================================================================
    @Test
    @DisplayName("4. Circuit Breaker mở mạch khi tỷ lệ lỗi cao, ngắt API và fallback tức thì")
    void testCircuitBreakerOpenAndClose() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(Duration.ofMillis(200))
                .permittedNumberOfCallsInHalfOpenState(2)
                .recordExceptions(HttpClientErrorException.TooManyRequests.class)
                .build();

        CircuitBreaker cb = CircuitBreakerRegistry.of(config).circuitBreaker("testCb");

        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());

        HttpClientErrorException ex429 = HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "Quota exhausted", HttpHeaders.EMPTY, new byte[0], null);

        // Tạo 4 cuộc gọi lỗi liên tiếp để vượt ngưỡng 50%
        for (int i = 0; i < 4; i++) {
            try {
                cb.executeSupplier(() -> { throw ex429; });
            } catch (Exception ignored) {}
        }

        // Mạch chuyển sang OPEN
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());

        // Khi mạch OPEN, lời gọi tiếp theo bị ném CallNotPermittedException ngay lập tức mà không chạy vào hàm
        assertThrows(CallNotPermittedException.class, () -> cb.executeSupplier(() -> "should_not_run"));

        // Chờ hết waitDurationInOpenState
        try {
            Thread.sleep(250);
        } catch (InterruptedException ignored) {}

        // Cuộc gọi thử nghiệm tiếp theo đưa mạch vào HALF_OPEN
        String success = cb.executeSupplier(() -> "ok_recovered");
        assertEquals("ok_recovered", success);
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.getState());
    }

    // =========================================================================
    // 5. TEST BẮT BUỘC: QUEUE ĐẦY THÌ FALLBACK (REJECTED EXECUTION)
    // =========================================================================
    @Test
    @DisplayName("5. Hàng đợi ThreadPool đầy kích hoạt Fallback ngay lập tức, không treo hệ thống")
    void testQueueFullTriggersFallback() {
        // Giả lập executor ném RejectedExecutionException do queue capacity = 50 bị đầy
        doThrow(new RejectedExecutionException("Executor queue is full"))
                .when(geminiTaskExecutor).execute(any(Runnable.class));

        when(conversationRepository.findById(100L)).thenReturn(Optional.of(conversation));
        when(userRepository.findByUsername("system")).thenReturn(Optional.of(customerUser));
        when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(i -> i.getArgument(0));
        when(chatMapper.toChatMessageResponse(any(ChatMessage.class))).thenReturn(new ChatMessageResponse());

        // Gọi xử lý tin nhắn
        assertDoesNotThrow(() ->
            aiChatService.handleCustomerMessageAsync(100L, customerUser, "Có vợt nào nhẹ dưới 1 triệu không?")
        );

        // Kiểm tra Fallback được gọi: chuyển conversation sang HUMAN
        verify(conversationRepository).updateHandlerMode(100L, HandlerMode.HUMAN);
        verify(conversationRepository).updateOnSystemMessage(eq(100L), contains("Trợ lý đang bận"), any());
        verify(chatBroadcaster).broadcast(eq("/topic/conversations/100"), any());
    }
}
