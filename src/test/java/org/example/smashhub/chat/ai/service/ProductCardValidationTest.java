package org.example.smashhub.chat.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.smashhub.catalog.entity.Product;
import org.example.smashhub.catalog.entity.ProductImage;
import org.example.smashhub.catalog.entity.ProductVariant;
import org.example.smashhub.catalog.repository.ProductImageRepository;
import org.example.smashhub.catalog.repository.ProductRepository;
import org.example.smashhub.catalog.repository.ProductVariantRepository;
import org.example.smashhub.chat.ai.client.GeminiApiClient;
import org.example.smashhub.chat.ai.client.dto.GeminiGenerateResponse;
import org.example.smashhub.chat.ai.config.AiProperties;
import org.example.smashhub.chat.ai.dto.ProductAdvisoryRecommendationDto;
import org.example.smashhub.chat.ai.limiter.AiBudgetTracker;
import org.example.smashhub.chat.ai.limiter.AiKillSwitch;
import org.example.smashhub.chat.ai.limiter.GeminiRateLimiter;
import org.example.smashhub.chat.ai.tool.ProductTools;
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
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductCardValidationTest {

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
        aiProperties.getRag().setSimilarityThreshold(0.40);
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
        when(chatMessageRepository.findByConversationId(anyLong(), any())).thenReturn(org.springframework.data.domain.Page.empty());
        when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chatMapper.toChatMessageResponse(any(ChatMessage.class))).thenAnswer(inv -> {
            ChatMessage msg = inv.getArgument(0);
            ChatMessageResponse res = new ChatMessageResponse();
            res.setContent(msg.getContent());
            res.setMessageType(msg.getMessageType());
            res.setReferencedProductId(msg.getReferencedProductId());
            return res;
        });
    }

    @Test
    @DisplayName("parseRecommendation: Trích xuất chính xác structured output từ JSON thuần và Markdown-wrapped JSON")
    void testStructuredOutputParsing() {
        // Trường hợp 1: JSON thuần
        String rawJson = "{\"text\": \"Dạ mình gợi ý bạn mẫu vợt này nhé!\", \"recommendedProductIds\": [101, 102]}";
        ProductAdvisoryRecommendationDto dto1 = aiAssistantService.parseRecommendation(rawJson);
        assertEquals("Dạ mình gợi ý bạn mẫu vợt này nhé!", dto1.getText());
        assertEquals(List.of(101L, 102L), dto1.getRecommendedProductIds());

        // Trường hợp 2: Markdown ```json code block
        String markdownJson = "Chào bạn!\n```json\n{\"text\": \"Mẫu vợt phù hợp với lối chơi công thủ: \", \"recommendedProductIds\": [201]}\n```\nChúc bạn chọn được vợt ưng ý!";
        ProductAdvisoryRecommendationDto dto2 = aiAssistantService.parseRecommendation(markdownJson);
        assertEquals(List.of(201L), dto2.getRecommendedProductIds());
        assertNotNull(dto2.getText());

        // Trường hợp 3: Văn bản thường không có thẻ sản phẩm
        String plainText = "Dạ vợt 4U nhẹ hơn 3U khoảng 5g nên sẽ dễ xoay sở hơn khi đánh đôi nhé.";
        ProductAdvisoryRecommendationDto dto3 = aiAssistantService.parseRecommendation(plainText);
        assertEquals(plainText, dto3.getText());
        assertTrue(dto3.getRecommendedProductIds().isEmpty());
    }

    @Test
    @DisplayName("Loại trừ các ID không tồn tại, ngừng bán hoặc hết hàng - Chỉ tạo PRODUCT_CARD cho ID hợp lệ và còn hàng")
    void testRejectionOfNonExistentAndOutOfStockProducts() {
        // LLM gợi ý 4 ID: 101 (hợp lệ), 102 (không tồn tại), 103 (ngừng bán), 104 (hết hàng)
        String llmResponse = "{\"text\": \"Em gợi ý bạn các mẫu sau:\", \"recommendedProductIds\": [101, 102, 103, 104]}";
        GeminiGenerateResponse geminiResp = new GeminiGenerateResponse();
        geminiResp.setCandidates(List.of(new GeminiGenerateResponse.Candidate(
                new GeminiGenerateResponse.Content(List.of(new GeminiGenerateResponse.Part(llmResponse)), "model"),
                "STOP"
        )));
        when(geminiApiClient.generateContent(anyString(), any())).thenReturn(geminiResp);

        // Product 101: Hợp lệ, ACTIVE, stock = 5
        Product p101 = new Product();
        p101.setId(101L);
        p101.setName("Yonex Astrox 88D Pro");
        p101.setSlug("yonex-astrox-88d-pro");
        p101.setPrice(BigDecimal.valueOf(4200000));
        p101.setProductStatus(ProductStatus.ACTIVE);
        when(productRepository.findById(101L)).thenReturn(Optional.of(p101));
        when(productVariantRepository.findByColorProductId(101L)).thenReturn(
                List.of(ProductVariant.builder().stock(5).active(VariantStatus.ACTIVE).build()));

        // Product 102: Không tồn tại trong DB
        when(productRepository.findById(102L)).thenReturn(Optional.empty());

        // Product 103: Tồn tại nhưng DISCONTINUED
        Product p103 = new Product();
        p103.setId(103L);
        p103.setName("Yonex Voltric Z-Force");
        p103.setProductStatus(ProductStatus.DISCONTINUED);
        when(productRepository.findById(103L)).thenReturn(Optional.of(p103));

        // Product 104: ACTIVE nhưng stock = 0
        Product p104 = new Product();
        p104.setId(104L);
        p104.setName("Victor Thruster K");
        p104.setProductStatus(ProductStatus.ACTIVE);
        when(productRepository.findById(104L)).thenReturn(Optional.of(p104));
        when(productVariantRepository.findByColorProductId(104L)).thenReturn(
                List.of(ProductVariant.builder().stock(0).active(VariantStatus.ACTIVE).build()));

        // Thực thi
        aiAssistantService.handleCustomerMessageAsync(100L, customerUser, "Tư vấn cho mình vợt nặng đầu tầm 4 triệu");

        // Kiểm tra lưu ChatMessage
        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMessageRepository, atLeast(2)).save(captor.capture());

        List<ChatMessage> savedMessages = captor.getAllValues();

        // 1 tin BOT TEXT giải thích
        long textMsgCount = savedMessages.stream().filter(m -> m.getMessageType() == MessageType.TEXT).count();
        assertEquals(1, textMsgCount);

        // Chỉ duy nhất 1 tin PRODUCT_CARD (cho ID 101), các ID 102, 103, 104 đã bị từ chối
        List<ChatMessage> cardMessages = savedMessages.stream().filter(m -> m.getMessageType() == MessageType.PRODUCT_CARD).toList();
        assertEquals(1, cardMessages.size());
        assertEquals(101L, cardMessages.get(0).getReferencedProductId());
        assertEquals(SenderRole.BOT, cardMessages.get(0).getSenderRole());
    }

    @Test
    @DisplayName("Giá và Slug trong thẻ PRODUCT_CARD luôn được nạp LIVE từ Database, không phụ thuộc vào LLM")
    void testLivePriceAndSlugUsedInCard() {
        Product product = new Product();
        product.setId(200L);
        product.setName("Vợt Yonex Nanoflare 1000Z");
        product.setSlug("yonex-nanoflare-1000z-chinh-hang");
        product.setPrice(BigDecimal.valueOf(4500000));
        product.setSalePrice(BigDecimal.valueOf(4190000));
        product.setProductStatus(ProductStatus.ACTIVE);

        ProductImage img = ProductImage.builder().url("https://smashhub.vn/nf1000z.jpg").isMain(ImagePriority.MAIN).build();
        when(productImageRepository.findByColorProductIdOrderByOrderIndexAsc(200L)).thenReturn(List.of(img));

        aiAssistantService.sendBotProductCard(100L, customerUser, product, "gemini-1.5-flash");

        ArgumentCaptor<ChatMessageResponse> respCaptor = ArgumentCaptor.forClass(ChatMessageResponse.class);
        verify(chatBroadcaster).broadcast(eq("/topic/conversations/100"), respCaptor.capture());

        ChatMessageResponse broadcasted = respCaptor.getValue();
        assertNotNull(broadcasted);
        assertEquals("Trợ lý AI", broadcasted.getSenderDisplayName());

        ProductSummaryCardDto card = broadcasted.getProductCard();
        assertNotNull(card);
        assertEquals(200L, card.getId());
        assertEquals("yonex-nanoflare-1000z-chinh-hang", card.getSlug());
        assertEquals(BigDecimal.valueOf(4500000), card.getPrice());
        assertEquals(BigDecimal.valueOf(4190000), card.getSalePrice());
        assertEquals("https://smashhub.vn/nf1000z.jpg", card.getThumbnailUrl());
    }
}
