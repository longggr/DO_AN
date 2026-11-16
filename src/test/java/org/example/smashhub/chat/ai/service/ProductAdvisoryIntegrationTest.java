package org.example.smashhub.chat.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.smashhub.catalog.entity.Brand;
import org.example.smashhub.catalog.entity.Category;
import org.example.smashhub.catalog.entity.Product;
import org.example.smashhub.catalog.entity.ProductImage;
import org.example.smashhub.catalog.entity.ProductVariant;
import org.example.smashhub.catalog.repository.ProductImageRepository;
import org.example.smashhub.catalog.repository.ProductRepository;
import org.example.smashhub.catalog.repository.ProductVariantRepository;
import org.example.smashhub.chat.ai.client.GeminiApiClient;
import org.example.smashhub.chat.ai.client.dto.GeminiGenerateRequest;
import org.example.smashhub.chat.ai.client.dto.GeminiGenerateResponse;
import org.example.smashhub.chat.ai.config.AiProperties;
import org.example.smashhub.chat.ai.dto.tool.ProductSearchResultDto;
import org.example.smashhub.chat.ai.limiter.AiBudgetTracker;
import org.example.smashhub.chat.ai.limiter.AiKillSwitch;
import org.example.smashhub.chat.ai.limiter.GeminiRateLimiter;
import org.example.smashhub.chat.ai.tool.OrderTools;
import org.example.smashhub.chat.ai.tool.ProductTools;
import org.example.smashhub.order.repository.OrderRepository;
import org.example.smashhub.chat.ai.vectorstore.VectorStore;
import org.example.smashhub.chat.dto.response.ChatMessageResponse;
import org.example.smashhub.chat.dto.response.ProductSummaryCardDto;
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
import org.example.smashhub.common.enums.ImagePriority;
import org.example.smashhub.common.enums.ProductStatus;
import org.example.smashhub.common.enums.ProductType;
import org.example.smashhub.common.enums.VariantStatus;
import org.example.smashhub.user.entity.User;
import org.example.smashhub.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductAdvisoryIntegrationTest {

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
    OrderTools orderTools;
    @Mock
    OrderRepository orderRepository;

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
        aiProperties.getRag().setMaxRecommendedProducts(3);

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

        when(chatMapper.toChatMessageResponse(any(ChatMessage.class))).thenAnswer(inv -> {
            ChatMessage msg = inv.getArgument(0);
            ChatMessageResponse res = new ChatMessageResponse();
            res.setContent(msg.getContent());
            res.setMessageType(msg.getMessageType());
            res.setReferencedProductId(msg.getReferencedProductId());
            res.setSenderRole(msg.getSenderRole());
            return res;
        });
    }

    @Test
    @DisplayName("Luồng trọn vẹn: Khách hỏi tư vấn vợt đánh đôi tầm 2 triệu -> Gọi tool -> Gợi ý bằng thẻ PRODUCT_CARD")
    void testEndToEndProductAdvisoryFlow() {
        String userQuery = "Tư vấn cho mình vợt nhẹ đầu nặng cho đánh đôi tầm 2 triệu";

        // 1. Tool searchProducts được mock trả về vợt Yonex Astrox 77 Play
        ProductSearchResultDto searchResult = ProductSearchResultDto.builder()
                .id(77L)
                .name("Yonex Astrox 77 Play")
                .slug("yonex-astrox-77-play")
                .brand("Yonex")
                .category("Vợt cầu lông")
                .price(BigDecimal.valueOf(1890000))
                .salePrice(BigDecimal.valueOf(1750000))
                .stock(6)
                .inStock(true)
                .thumbnailUrl("https://smashhub.vn/images/ax77play.jpg")
                .keyAttributes(Map.of("weight", "4U", "balance_point", "Hơi nặng đầu", "stiffness", "Dẻo"))
                .build();
        when(productTools.searchProducts(anyString(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(searchResult));

        // 2. Mock 2 lượt gọi Gemini API:
        // Lượt 1: Gemini quyết định gọi tool searchProducts
        String turn1ToolCall = "{\"tool\": \"searchProducts\", \"arguments\": {\"query\": \"vợt nhẹ đầu nặng\", \"maxPrice\": 2000000}}";
        GeminiGenerateResponse turn1Resp = new GeminiGenerateResponse();
        turn1Resp.setCandidates(List.of(new GeminiGenerateResponse.Candidate(
                new GeminiGenerateResponse.Content(List.of(new GeminiGenerateResponse.Part(turn1ToolCall)), "model"),
                "STOP"
        )));

        // Lượt 2: Sau khi có kết quả tool, Gemini trả lời và gợi ý ID 77
        String turn2Recommendation = """
                {
                    "text": "Dạ trong tầm giá dưới 2 triệu, em gợi ý bạn cây Yonex Astrox 77 Play. Vợt có trọng lượng 4U nhẹ nhàng, điểm cân bằng hơi nặng đầu kết hợp thân dẻo trợ lực cực tốt cho các pha gài cầu và đập cầu nhanh trong đánh đôi ạ!",
                    "recommendedProductIds": [77]
                }
                """;
        GeminiGenerateResponse turn2Resp = new GeminiGenerateResponse();
        turn2Resp.setCandidates(List.of(new GeminiGenerateResponse.Candidate(
                new GeminiGenerateResponse.Content(List.of(new GeminiGenerateResponse.Part(turn2Recommendation)), "model"),
                "STOP"
        )));

        // Gemini trả về turn1 rồi turn2
        when(geminiApiClient.generateContent(anyString(), any(GeminiGenerateRequest.class)))
                .thenReturn(turn1Resp)
                .thenReturn(turn2Resp);

        // 3. Mock DB trả về dữ liệu LIVE cho Product 77
        Brand yonex = new Brand();
        yonex.setName("Yonex");
        Category cat = new Category();
        cat.setName("Vợt cầu lông");

        Product p77 = new Product();
        p77.setId(77L);
        p77.setName("Yonex Astrox 77 Play");
        p77.setSlug("yonex-astrox-77-play");
        p77.setBrand(yonex);
        p77.setCategory(cat);
        p77.setType(ProductType.RACKET);
        p77.setPrice(BigDecimal.valueOf(1890000));
        p77.setSalePrice(BigDecimal.valueOf(1750000));
        p77.setProductStatus(ProductStatus.ACTIVE);

        when(productRepository.findById(77L)).thenReturn(Optional.of(p77));
        when(productVariantRepository.findByColorProductId(77L)).thenReturn(
                List.of(ProductVariant.builder().sku("AX77P-4U").stock(6).active(VariantStatus.ACTIVE).build()));

        ProductImage img = ProductImage.builder().url("https://smashhub.vn/images/ax77play.jpg").isMain(ImagePriority.MAIN).build();
        when(productImageRepository.findByColorProductIdOrderByOrderIndexAsc(77L)).thenReturn(List.of(img));

        // 4. Kích hoạt xử lý tin nhắn
        aiAssistantService.handleCustomerMessageAsync(100L, customerUser, userQuery);

        // 5. Xác minh kết quả:
        // Đã gọi Gemini API 2 lượt (1 lượt tool calling + 1 lượt sinh kết quả)
        verify(geminiApiClient, times(2)).generateContent(anyString(), any());

        // Kiểm tra các tin nhắn được lưu vào Database
        ArgumentCaptor<ChatMessage> msgCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMessageRepository, atLeast(2)).save(msgCaptor.capture());

        List<ChatMessage> saved = msgCaptor.getAllValues();

        // Tin nhắn 1: TEXT giải thích từ Trợ lý AI
        ChatMessage textMsg = saved.stream()
                .filter(m -> m.getMessageType() == MessageType.TEXT && m.getSenderRole() == SenderRole.BOT)
                .findFirst()
                .orElse(null);
        assertNotNull(textMsg, "Phải có tin nhắn giải thích TEXT của BOT");
        assertTrue(textMsg.getContent().contains("Yonex Astrox 77 Play"));

        // Tin nhắn 2: PRODUCT_CARD chứa snapshot dữ liệu LIVE
        ChatMessage cardMsg = saved.stream()
                .filter(m -> m.getMessageType() == MessageType.PRODUCT_CARD)
                .findFirst()
                .orElse(null);
        assertNotNull(cardMsg, "Phải có tin nhắn PRODUCT_CARD của BOT");
        assertEquals(77L, cardMsg.getReferencedProductId());
        assertEquals(SenderRole.BOT, cardMsg.getSenderRole());
        assertTrue(cardMsg.getSnapshotJson().contains("1890000"));
        assertTrue(cardMsg.getSnapshotJson().contains("1750000"));
        assertTrue(cardMsg.getSnapshotJson().contains("yonex-astrox-77-play"));

        // Kiểm tra phát tin WebSocket STOMP tới topic conversation
        ArgumentCaptor<ChatMessageResponse> stompCaptor = ArgumentCaptor.forClass(ChatMessageResponse.class);
        verify(chatBroadcaster, atLeast(2)).broadcast(eq("/topic/conversations/100"), stompCaptor.capture());

        List<ChatMessageResponse> stompMessages = stompCaptor.getAllValues();
        ChatMessageResponse broadcastedCard = stompMessages.stream()
                .filter(r -> r.getMessageType() == MessageType.PRODUCT_CARD)
                .findFirst()
                .orElse(null);

        assertNotNull(broadcastedCard);
        assertEquals("Trợ lý AI", broadcastedCard.getSenderDisplayName());
        ProductSummaryCardDto cardDto = broadcastedCard.getProductCard();
        assertNotNull(cardDto);
        assertEquals(77L, cardDto.getId());
        assertEquals("Yonex Astrox 77 Play", cardDto.getName());
        assertEquals(BigDecimal.valueOf(1890000), cardDto.getPrice());
        assertEquals(BigDecimal.valueOf(1750000), cardDto.getSalePrice());
    }
}

