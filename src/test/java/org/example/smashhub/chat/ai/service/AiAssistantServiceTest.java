package org.example.smashhub.chat.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.smashhub.chat.ai.client.GeminiApiClient;
import org.example.smashhub.chat.ai.client.dto.GeminiGenerateRequest;
import org.example.smashhub.chat.ai.client.dto.GeminiGenerateResponse;
import org.example.smashhub.chat.ai.config.AiProperties;
import org.example.smashhub.chat.ai.limiter.AiBudgetTracker;
import org.example.smashhub.chat.ai.limiter.AiKillSwitch;
import org.example.smashhub.chat.ai.limiter.GeminiRateLimiter;
import org.example.smashhub.chat.ai.vectorstore.Document;
import org.example.smashhub.chat.ai.vectorstore.SearchRequest;
import org.example.smashhub.chat.ai.vectorstore.VectorStore;
import org.example.smashhub.chat.dto.response.ChatMessageResponse;
import org.example.smashhub.chat.entity.ChatMessage;
import org.example.smashhub.chat.entity.Conversation;
import org.example.smashhub.chat.enums.ConversationStatus;
import org.example.smashhub.chat.enums.HandlerMode;
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
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.domain.PageImpl;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiAssistantServiceTest {

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
        aiProperties.getRag().setSimilarityThreshold(0.65);

        doAnswer(invocation -> {
            Runnable r = invocation.getArgument(0);
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
                resourceLoader
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
    }

    @Test
    @DisplayName("Silence on HUMAN mode: Bot im lặng tuyệt đối khi hội thoại ở chế độ HUMAN")
    void testSilenceOnHumanMode() {
        conversation.setHandlerMode(HandlerMode.HUMAN);
        when(conversationRepository.findById(100L)).thenReturn(Optional.of(conversation));

        aiAssistantService.handleCustomerMessageAsync(100L, customerUser, "Cho mình hỏi về bảo hành vợt");

        verifyNoInteractions(geminiApiClient);
        verifyNoInteractions(vectorStore);
        verify(chatMessageRepository, never()).save(any());
    }

    @Test
    @DisplayName("Handoff trigger: Tự động chuyển HUMAN và cập nhật OPEN khi khách muốn gặp nhân viên")
    void testHumanHandoffTrigger() {
        when(conversationRepository.findById(100L)).thenReturn(Optional.of(conversation));
        when(conversationRepository.getReferenceById(100L)).thenReturn(conversation);
        when(userRepository.findByUsername(anyString())).thenReturn(Optional.of(customerUser));
        when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chatMapper.toChatMessageResponse(any())).thenReturn(new ChatMessageResponse());

        aiAssistantService.handleCustomerMessageAsync(100L, customerUser, "Tôi muốn gặp nhân viên tư vấn để giải quyết đơn hàng");

        verify(conversationRepository).updateHandlerMode(100L, HandlerMode.HUMAN);
        verify(conversationRepository).updateStatus(100L, ConversationStatus.OPEN);
        verifyNoInteractions(geminiApiClient);
        verify(chatBroadcaster, atLeastOnce()).broadcast(eq("/topic/conversations/100"), any());
    }

    @Test
    @DisplayName("Similarity Threshold Cutoff: Không có đoạn kiến thức nào đạt ngưỡng -> KHÔNG GỌI LLM, đề nghị chuyển nhân viên")
    void testSimilarityThresholdCutoff() {
        when(conversationRepository.findById(100L)).thenReturn(Optional.of(conversation));
        when(conversationRepository.getReferenceById(100L)).thenReturn(conversation);
        when(aiBudgetTracker.checkDailyBudget()).thenReturn(new AiBudgetTracker.BudgetCheckResult(true, BigDecimal.ZERO, BigDecimal.TEN, 0.0));
        when(aiFaqService.findFaqAnswer(anyString())).thenReturn(Optional.empty());

        GeminiRateLimiter.RateLimitResult allowedRate = GeminiRateLimiter.RateLimitResult.ok(100L);
        when(geminiRateLimiter.tryAcquireChat(anyString(), eq(10L), anyString())).thenReturn(allowedRate);

        // Giả lập không tìm thấy document nào đạt similarityThreshold >= 0.65
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(Collections.emptyList());

        when(userRepository.findByUsername(anyString())).thenReturn(Optional.of(customerUser));
        when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chatMapper.toChatMessageResponse(any())).thenReturn(new ChatMessageResponse());

        aiAssistantService.handleCustomerMessageAsync(100L, customerUser, "Hôm nay thời tiết ở Paris thế nào bạn ơi?");

        // BẮT BUỘC: Không gọi LLM
        verifyNoInteractions(geminiApiClient);
        // Chuyển sang HUMAN
        verify(conversationRepository).updateHandlerMode(100L, HandlerMode.HUMAN);
        verify(conversationRepository).updateStatus(100L, ConversationStatus.OPEN);
    }

    @Test
    @DisplayName("Rate limit fallback: Khi limiter từ chối thì fallback sang chuyển nhân viên")
    void testRateLimitFallback() {
        when(conversationRepository.findById(100L)).thenReturn(Optional.of(conversation));
        when(conversationRepository.getReferenceById(100L)).thenReturn(conversation);
        when(aiBudgetTracker.checkDailyBudget()).thenReturn(new AiBudgetTracker.BudgetCheckResult(true, BigDecimal.ZERO, BigDecimal.TEN, 0.0));
        when(aiFaqService.findFaqAnswer(anyString())).thenReturn(Optional.empty());

        // Limiter từ chối
        GeminiRateLimiter.RateLimitResult deniedRate = GeminiRateLimiter.RateLimitResult.denied("USER_RPM_EXCEEDED");
        when(geminiRateLimiter.tryAcquireChat(anyString(), eq(10L), anyString())).thenReturn(deniedRate);

        when(userRepository.findByUsername(anyString())).thenReturn(Optional.of(customerUser));
        when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chatMapper.toChatMessageResponse(any())).thenReturn(new ChatMessageResponse());

        aiAssistantService.handleCustomerMessageAsync(100L, customerUser, "Chính sách đổi trả thế nào?");

        verifyNoInteractions(geminiApiClient);
        verify(conversationRepository).updateHandlerMode(100L, HandlerMode.HUMAN);
    }

    @Test
    @DisplayName("Luồng đầy đủ thành công: RAG tìm thấy tài liệu -> gọi Gemini mock -> lưu BOT message và broadcast")
    void testFullSuccessfulFlowWithMockLlm() {
        when(conversationRepository.findById(100L)).thenReturn(Optional.of(conversation));
        when(aiBudgetTracker.checkDailyBudget()).thenReturn(new AiBudgetTracker.BudgetCheckResult(true, BigDecimal.ZERO, BigDecimal.TEN, 0.0));
        when(aiFaqService.findFaqAnswer(anyString())).thenReturn(Optional.empty());

        GeminiRateLimiter.RateLimitResult allowedRate = GeminiRateLimiter.RateLimitResult.ok(150L);
        when(geminiRateLimiter.tryAcquireChat(anyString(), eq(10L), anyString())).thenReturn(allowedRate);

        // Vector store trả về tài liệu hợp lệ
        Document matchedDoc = Document.builder()
                .id("doc_1_chunk_0")
                .content("Bảo hành 90 ngày cho các dòng vợt Yonex chính hãng.")
                .metadata(Map.of("title", "Chính sách bảo hành", "category", "WARRANTY", "documentId", 1L))
                .score(0.89)
                .build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(matchedDoc));

        when(chatMessageRepository.findByConversationId(eq(100L), any())).thenReturn(new PageImpl<>(Collections.emptyList()));

        // Mock response từ Gemini
        GeminiGenerateResponse mockLlmResp = GeminiGenerateResponse.ofText(
                "Dạ vợt Yonex chính hãng tại SmashHub được bảo hành 90 ngày tính từ ngày mua bạn nhé!");
        when(geminiApiClient.generateContent(anyString(), any(GeminiGenerateRequest.class))).thenReturn(mockLlmResp);

        when(userRepository.findByUsername(anyString())).thenReturn(Optional.of(customerUser));
        when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> inv.getArgument(0));

        ChatMessageResponse mockRespDto = new ChatMessageResponse();
        when(chatMapper.toChatMessageResponse(any())).thenReturn(mockRespDto);

        aiAssistantService.handleCustomerMessageAsync(100L, customerUser, "Vợt Yonex bảo hành bao lâu shop?");

        // Xác minh gọi LLM
        verify(geminiApiClient).generateContent(eq("gemini-1.5-flash"), any(GeminiGenerateRequest.class));

        // Xác minh lưu tin nhắn với SenderRole.BOT
        verify(chatMessageRepository).save(argThat(msg ->
                msg.getSenderRole() == SenderRole.BOT &&
                msg.getAiModel().equals("gemini-1.5-flash") &&
                msg.getAiSources().contains("Chính sách bảo hành")
        ));

        // Xác minh broadcast WebSocket
        verify(chatBroadcaster).broadcast(eq("/topic/conversations/100"), any(ChatMessageResponse.class));
        assertEquals("Trợ lý AI", mockRespDto.getSenderDisplayName());
    }
}
