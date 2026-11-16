package org.example.smashhub.chat.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.smashhub.catalog.repository.ProductImageRepository;
import org.example.smashhub.catalog.repository.ProductRepository;
import org.example.smashhub.catalog.repository.ProductVariantRepository;
import org.example.smashhub.chat.ai.client.GeminiApiClient;
import org.example.smashhub.chat.ai.client.dto.GeminiGenerateResponse;
import org.example.smashhub.chat.ai.config.AiProperties;
import org.example.smashhub.chat.ai.dto.tool.ProductDetailDto;
import org.example.smashhub.chat.ai.limiter.AiBudgetTracker;
import org.example.smashhub.chat.ai.limiter.AiKillSwitch;
import org.example.smashhub.chat.ai.limiter.GeminiRateLimiter;
import org.example.smashhub.chat.ai.tool.ProductTools;
import org.example.smashhub.chat.ai.vectorstore.VectorStore;
import org.example.smashhub.chat.dto.response.ChatMessageResponse;
import org.example.smashhub.chat.entity.ChatMessage;
import org.example.smashhub.chat.entity.Conversation;
import org.example.smashhub.chat.enums.ConversationStatus;
import org.example.smashhub.chat.enums.HandlerMode;
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
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.domain.Page;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ToolIterationLimitTest {

    @Mock
    GeminiApiClient geminiApiClient;
    @Mock
    GeminiRateLimiter geminiRateLimiter;
    @Mock
    AiBudgetTracker aiBudgetTracker;
    @Mock
    AiKillSwitch aiKillSwitch;
    @Mock
    AiFaqService aiFaqService;
    @Mock
    VectorStore vectorStore;
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
    ResourceLoader resourceLoader;
    @Mock
    ThreadPoolTaskExecutor geminiTaskExecutor;

    @Mock
    ProductTools productTools;
    @Mock
    ProductRepository productRepository;
    @Mock
    ProductVariantRepository productVariantRepository;
    @Mock
    ProductImageRepository productImageRepository;
    @Mock
    org.example.smashhub.chat.ai.tool.OrderTools orderTools;
    @Mock
    org.example.smashhub.order.repository.OrderRepository orderRepository;

    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    AiProperties aiProperties;
    AiAssistantService aiAssistantService;

    User customerUser;
    Conversation conversation;

    @BeforeEach
    void setUp() {
        aiProperties = new AiProperties();
        aiProperties.setEnabled(true);
        aiProperties.setChatModel("gemini-1.5-flash");
        aiProperties.getTools().setMaxIterations(3);

        doAnswer(inv -> {
            Runnable r = inv.getArgument(0);
            r.run();
            return null;
        }).when(geminiTaskExecutor).execute(any(Runnable.class));

        aiAssistantService = new AiAssistantService(
                geminiApiClient,
                geminiRateLimiter,
                aiBudgetTracker,
                aiKillSwitch,
                aiFaqService,
                vectorStore,
                conversationRepository,
                chatMessageRepository,
                userRepository,
                chatBroadcaster,
                chatMapper,
                aiProperties,
                objectMapper,
                resourceLoader,
                productTools,
                productRepository,
                productVariantRepository,
                productImageRepository,
                orderTools,
                orderRepository
        );
        aiAssistantService.setGeminiTaskExecutor(geminiTaskExecutor);

        customerUser = new User();
        customerUser.setId(10L);
        customerUser.setUsername("customer1");

        conversation = Conversation.builder()
                .customer(customerUser)
                .handlerMode(HandlerMode.AI)
                .status(ConversationStatus.OPEN)
                .build();
        conversation.setId(100L);

        when(aiKillSwitch.isAiEnabled()).thenReturn(true);
        when(aiBudgetTracker.checkDailyBudget()).thenReturn(new AiBudgetTracker.BudgetCheckResult(true, BigDecimal.ZERO, BigDecimal.TEN, 0.0));
        when(aiFaqService.findFaqAnswer(anyString())).thenReturn(Optional.empty());
        when(geminiRateLimiter.tryAcquireChat(anyString(), anyLong(), anyString())).thenReturn(GeminiRateLimiter.RateLimitResult.ok(100L));
        when(conversationRepository.findById(100L)).thenReturn(Optional.of(conversation));
        when(conversationRepository.getReferenceById(100L)).thenReturn(conversation);
        when(userRepository.findByUsername(anyString())).thenReturn(Optional.of(customerUser));
        when(chatMessageRepository.findByConversationId(anyLong(), any())).thenReturn(Page.empty());
        when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chatMapper.toChatMessageResponse(any())).thenReturn(new ChatMessageResponse());
    }

    @Test
    @DisplayName("Giới hạn số vòng lặp gọi tool tối đa (maxIterations = 3) để tránh vòng lặp vô hạn và bảo vệ quota")
    void testMaxIterationsCutoff() {
        // Giả lập LLM liên tục gọi tool không dừng
        String toolCallJson = "{\"tool\": \"getProductDetail\", \"arguments\": {\"productId\": 101}}";
        GeminiGenerateResponse toolCallResp = new GeminiGenerateResponse();
        toolCallResp.setCandidates(List.of(new GeminiGenerateResponse.Candidate(
                new GeminiGenerateResponse.Content(List.of(new GeminiGenerateResponse.Part(toolCallJson)), "model"),
                "STOP"
        )));

        when(geminiApiClient.generateContent(anyString(), any())).thenReturn(toolCallResp);
        when(productTools.getProductDetail(101L)).thenReturn(ProductDetailDto.builder().id(101L).name("Test Product").build());

        aiAssistantService.handleCustomerMessageAsync(100L, customerUser, "Tư vấn chi tiết cây vợt này cho mình");

        // Tool chỉ được gọi tối đa 3 lần (maxIterations)
        verify(productTools, times(3)).getProductDetail(101L);
        // LLM ban đầu gọi 1 lần + 3 lần retry sau tool = 4 lần gọi geminiApiClient
        verify(geminiApiClient, times(4)).generateContent(anyString(), any());
    }

    @Test
    @DisplayName("Dừng vòng lặp gọi tool ngay lập tức khi RateLimiter từ chối giữa chừng")
    void testToolLoopStopsWhenRateLimitBreachedMidway() {
        String toolCallJson = "{\"tool\": \"getProductDetail\", \"arguments\": {\"productId\": 101}}";
        GeminiGenerateResponse toolCallResp = new GeminiGenerateResponse();
        toolCallResp.setCandidates(List.of(new GeminiGenerateResponse.Candidate(
                new GeminiGenerateResponse.Content(List.of(new GeminiGenerateResponse.Part(toolCallJson)), "model"),
                "STOP"
        )));

        when(geminiApiClient.generateContent(anyString(), any())).thenReturn(toolCallResp);
        when(productTools.getProductDetail(101L)).thenReturn(ProductDetailDto.builder().id(101L).name("Test Product").build());

        // Lần đầu cho phép, nhưng đến vòng lặp thứ 2 thì limiter từ chối
        when(geminiRateLimiter.tryAcquireChat(anyString(), eq(10L), anyString()))
                .thenReturn(GeminiRateLimiter.RateLimitResult.ok(100L))  // Lần 1: processCustomerMessage OK
                .thenReturn(GeminiRateLimiter.RateLimitResult.denied("PROJECT_RPM_EXCEEDED")); // Lần 2 trong tool loop bị chặn!

        aiAssistantService.handleCustomerMessageAsync(100L, customerUser, "Xem thông số vợt này giúp mình");

        // Tool chỉ chạy đúng 1 lần rồi lập tức ngắt vòng lặp
        verify(productTools, times(1)).getProductDetail(101L);
        // Chỉ gọi LLM ban đầu 1 lần, không gọi lần 2
        verify(geminiApiClient, times(1)).generateContent(anyString(), any());
    }
}

