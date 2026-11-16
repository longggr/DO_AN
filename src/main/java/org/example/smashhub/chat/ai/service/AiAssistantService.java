package org.example.smashhub.chat.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
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
import org.example.smashhub.chat.ai.dto.ProductAdvisoryRecommendationDto;
import org.example.smashhub.chat.ai.dto.tool.OrderDetailDto;
import org.example.smashhub.chat.ai.dto.tool.OrderSummaryDto;
import org.example.smashhub.chat.ai.dto.tool.ProductDetailDto;
import org.example.smashhub.chat.ai.dto.tool.ProductSearchResultDto;
import org.example.smashhub.chat.ai.dto.tool.ProductStockDto;
import org.example.smashhub.chat.ai.limiter.AiBudgetTracker;
import org.example.smashhub.chat.ai.limiter.AiKillSwitch;
import org.example.smashhub.chat.ai.limiter.GeminiRateLimiter;
import org.example.smashhub.chat.ai.security.AiChatSecurityContext;
import org.example.smashhub.chat.ai.tool.OrderTools;
import org.example.smashhub.chat.ai.tool.ProductTools;
import org.example.smashhub.chat.ai.vectorstore.Document;
import org.example.smashhub.chat.ai.vectorstore.SearchRequest;
import org.example.smashhub.chat.ai.vectorstore.VectorStore;
import org.example.smashhub.chat.dto.event.ConversationUpdateEvent;
import org.example.smashhub.chat.dto.event.TypingEvent;
import org.example.smashhub.chat.dto.response.ChatMessageResponse;
import org.example.smashhub.chat.dto.response.OrderSummaryCardDto;
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
import org.example.smashhub.order.entity.Order;
import org.example.smashhub.order.repository.OrderRepository;
import org.example.smashhub.user.entity.User;
import org.example.smashhub.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AiAssistantService {

    final GeminiApiClient geminiApiClient;
    final GeminiRateLimiter geminiRateLimiter;
    final AiBudgetTracker aiBudgetTracker;
    final AiKillSwitch aiKillSwitch;
    final AiFaqService aiFaqService;
    final VectorStore vectorStore;
    final ConversationRepository conversationRepository;
    final ChatMessageRepository chatMessageRepository;
    final UserRepository userRepository;
    final ChatBroadcaster chatBroadcaster;
    final ChatMapper chatMapper;
    final AiProperties aiProperties;
    final ObjectMapper objectMapper;
    final ResourceLoader resourceLoader;

    final ProductTools productTools;
    final ProductRepository productRepository;
    final ProductVariantRepository productVariantRepository;
    final ProductImageRepository productImageRepository;

    final OrderTools orderTools;
    final org.example.smashhub.order.repository.OrderRepository orderRepository;

    @Autowired(required = false)
    org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @Autowired
    @Qualifier("geminiTaskExecutor")
    ThreadPoolTaskExecutor geminiTaskExecutor;

    public void setGeminiTaskExecutor(ThreadPoolTaskExecutor geminiTaskExecutor) {
        this.geminiTaskExecutor = geminiTaskExecutor;
    }

    public void setRedisTemplate(org.springframework.data.redis.core.StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Autowired(required = false)
    MeterRegistry meterRegistry;

    String systemPromptTemplate;

    @PostConstruct
    public void initPromptTemplate() {
        try {
            Resource res = resourceLoader.getResource("classpath:prompts/support-system.st");
            if (res.exists()) {
                this.systemPromptTemplate = res.getContentAsString(StandardCharsets.UTF_8);
            } else {
                log.warn("Prompt template 'classpath:prompts/support-system.st' not found, using fallback");
                this.systemPromptTemplate = fallbackPromptTemplate();
            }
        } catch (IOException e) {
            log.error("Failed to load prompt template support-system.st", e);
            this.systemPromptTemplate = fallbackPromptTemplate();
        }
    }

    /**
     * Tiếp nhận xử lý tin nhắn khách hàng bất đồng bộ trên thread pool chuyên biệt.
     */
    public void handleCustomerMessageAsync(Long conversationId, User customer, String userMessage) {
        try {
            geminiTaskExecutor.execute(() -> processCustomerMessage(conversationId, customer, userMessage));
        } catch (RejectedExecutionException rex) {
            log.warn("[AI THREAD POOL FULL] Queue capacity exceeded for conversationId={}. Routing to Human.", conversationId);
            executeFallback(conversationId, customer, userMessage, "QUEUE_FULL");
        }
    }

    private void processCustomerMessage(Long conversationId, User customer, String userMessage) {
        try {
            // NGUYÊN TẮC BẢO MẬT GIAI ĐOẠN 3: Thiết lập ngữ cảnh định danh ThreadLocal
            AiChatSecurityContext.setUserId(customer.getId());
            AiChatSecurityContext.setIsAdmin(false);

            // 1. Kiểm tra trạng thái AI và chế độ hội thoại
            if (!aiKillSwitch.isAiEnabled() || !aiProperties.isEnabled()) {
                log.info("AI is disabled. Skipping AI handling for conversationId={}", conversationId);
                return;
            }

            Conversation conversation = conversationRepository.findById(conversationId).orElse(null);
            if (conversation == null) {
                return;
            }

            if (conversation.getHandlerMode() != HandlerMode.AI) {
                log.debug("Conversation {} is in HUMAN mode. Bot is silent.", conversationId);
                return;
            }

            // 2. Nhận diện ý định gặp nhân viên trực tiếp (Direct Human Handoff)
            if (isHumanHandoffIntent(userMessage)) {
                log.info("Direct human handoff requested by customer in conversationId={}", conversationId);
                triggerHumanHandoff(conversationId, customer,
                        "Dạ, em đã chuyển cuộc trò chuyện sang chuyên viên tư vấn SmashHub. Chuyên viên sẽ hỗ trợ bạn ngay ạ!",
                        "CUSTOMER_REQUESTED");
                return;
            }

            // 2b. Nhận diện chủ đề nhạy cảm & yêu cầu sửa/hủy đơn (GIAI ĐOẠN 3)
            if (isSensitiveTopicOrOrderMutation(userMessage)) {
                log.info("Sensitive topic or order mutation requested in conversationId={}", conversationId);
                triggerHumanHandoff(conversationId, customer,
                        "Dạ đối với yêu cầu thay đổi đơn hàng / khiếu nại chất lượng / hỗ trợ thanh toán, em xin phép chuyển ngay cuộc trò chuyện sang chuyên viên tư vấn để kiểm tra và xử lý trực tiếp cho bạn nhé ạ!",
                        "SENSITIVE_TOPIC");
                return;
            }

            // 2c. Tiền xử lý tin nhắn: Lọc ký tự rác / Chào hỏi ngắn (Zero-API cost)
            String cleanText = userMessage != null ? userMessage.trim().toLowerCase() : "";
            if (isJunkOrShortMessage(userMessage)) {
                log.info("Junk/short message intercepted for conversationId={}: '{}'", conversationId, userMessage);
                String gibberishKey = "chat:gibberish:conv:" + conversationId;
                long gibberishCount = 1;
                if (redisTemplate != null) {
                    try {
                        Long count = redisTemplate.opsForValue().increment(gibberishKey);
                        gibberishCount = count != null ? count : 1;
                        redisTemplate.expire(gibberishKey, java.time.Duration.ofMinutes(10));
                    } catch (Exception e) {
                        log.warn("Redis error incrementing gibberish counter", e);
                    }
                }
                if (gibberishCount >= 2 && isMeaninglessGibberish(cleanText)) {
                    if (redisTemplate != null) {
                        try { redisTemplate.delete(gibberishKey); } catch (Exception ignored) {}
                    }
                    log.info("Repeated gibberish detected (count={}) in convId={}. Handing off to human.", gibberishCount, conversationId);
                    triggerHumanHandoff(conversationId, customer,
                            "Dạ em nhận thấy yêu cầu của bạn có thể cần hỗ trợ trực tiếp. Em xin phép chuyển cuộc trò chuyện sang chuyên viên tư vấn SmashHub để phục vụ bạn tốt hơn nhé!",
                            "GIBBERISH_REPEATED");
                    return;
                }

                sendBotResponse(conversationId, customer,
                        "Dạ SmashHub xin chào! Em có thể hỗ trợ anh/chị tìm dòng vợt hay phụ kiện nào ạ?",
                        "[]",
                        "rule-preprocessor");
                return;
            }

            // 2d. Tiền xử lý tin nhắn: Điều hướng câu hỏi mở/phổ biến (Zero-API cost)
            if (isBroadAdvisoryKeyword(userMessage)) {
                log.info("Broad racket advisory keyword intercepted for conversationId={}: '{}'", conversationId, userMessage);
                resetGibberishCounter(conversationId);
                String promptRacketQuestionnaire = "Dạ SmashHub xin chào! Để em có thể tư vấn cây vợt phù hợp nhất cho anh/chị, anh/chị chia sẻ thêm giúp em 3 thông tin nhé:\n"
                        + "1. **Lối chơi của mình**: Anh/chị thiên về tấn công uy lực, phòng thủ phản tạt, hay công thủ toàn diện ạ?\n"
                        + "2. **Trình độ hiện tại**: Anh/chị mới tập chơi hay đã chơi phong trào lâu năm ạ?\n"
                        + "3. **Mức giá mong muốn**: Ngân sách dự kiến của anh/chị khoảng bao nhiêu ạ?";
                sendBotResponse(conversationId, customer, promptRacketQuestionnaire, "[]", "rule-preprocessor");
                return;
            }

            if (isBroadShoeAdvisoryKeyword(userMessage)) {
                log.info("Broad shoe advisory keyword intercepted for conversationId={}: '{}'", conversationId, userMessage);
                resetGibberishCounter(conversationId);
                String promptShoeQuestionnaire = "Dạ để em có thể tư vấn đôi giày cầu lông vừa vặn và êm ái nhất cho anh/chị, anh/chị chia sẻ thêm giúp em một vài thông tin nhé:\n"
                        + "1. **Size giày** hoặc chiều dài bàn chân của mình?\n"
                        + "2. **Form bàn chân**: Bàn chân của mình bè ngang hay thon gọn ạ?\n"
                        + "3. **Mức giá mong muốn**: Ngân sách dự kiến của mình khoảng bao nhiêu ạ?";
                sendBotResponse(conversationId, customer, promptShoeQuestionnaire, "[]", "rule-preprocessor");
                return;
            }

            // Tin nhắn hợp lệ -> reset bộ đếm ký tự rác
            resetGibberishCounter(conversationId);

            // 3. Kiểm tra Ngân sách hôm nay
            AiBudgetTracker.BudgetCheckResult budgetCheck = aiBudgetTracker.checkDailyBudget();
            if (!budgetCheck.isAllowed()) {
                log.warn("Daily AI budget exceeded. Disabling AI for conversationId={}", conversationId);
                executeFallback(conversationId, customer, userMessage, "BUDGET_EXCEEDED");
                return;
            }

            // 4. Kiểm tra Cache FAQ đã chuẩn hoá (áp dụng cho câu hỏi chính sách/dịch vụ tĩnh)
            Optional<String> cachedFaq = aiFaqService.findFaqAnswer(userMessage);
            if (cachedFaq.isPresent()) {
                log.info("FAQ Cache hit for conversationId={}", conversationId);
                sendBotResponse(conversationId, customer, cachedFaq.get(), "[{\"title\": \"Câu hỏi thường gặp FAQ\", \"category\": \"FAQ\"}]", "faq-cache");
                return;
            }

            // 5. Kiểm tra Quota Limiter
            String chatModel = aiProperties.getChatModel();
            GeminiRateLimiter.RateLimitResult rateCheck = geminiRateLimiter.tryAcquireChat(chatModel, customer.getId(), userMessage);
            if (!rateCheck.isAllowed()) {
                log.warn("Rate limit breached for conversationId={} (reason={})", conversationId, rateCheck.getReason());
                if (meterRegistry != null) {
                    meterRegistry.counter("smashhub.ai.rate_limited", "reason", rateCheck.getReason()).increment();
                }
                executeFallback(conversationId, customer, userMessage, rateCheck.getReason());
                return;
            }

            long estimatedTokens = rateCheck.getEstimatedTokens();

            // 6. Phát sự kiện Bot đang soạn tin (Typing Indicator)
            sendTypingStatus(conversationId, true);

            try {
                // 7. Nhận diện nhu cầu tư vấn sản phẩm (Phase 2) & Tra cứu đơn hàng (Phase 3)
                boolean isProductIntent = isProductAdvisoryIntent(userMessage);
                List<ProductSearchResultDto> liveProducts = Collections.emptyList();
                if (isProductIntent) {
                    liveProducts = productTools.searchProducts(userMessage, null, null, null, null, 5);
                }

                boolean isOrderIntent = isOrderLookupIntent(userMessage);
                String customerOrdersText = "";
                String orderDetailsText = "";
                Set<String> sessionOrderCodes = new HashSet<>();

                if (isOrderIntent) {
                    List<OrderSummaryDto> myOrders = orderTools.getMyOrders(3);
                    if (myOrders != null && !myOrders.isEmpty()) {
                        customerOrdersText = formatCustomerOrders(myOrders);
                        for (OrderSummaryDto o : myOrders) {
                            if (o.getOrderCode() != null) sessionOrderCodes.add(o.getOrderCode().toUpperCase());
                            if (o.getOrderId() != null) {
                                sessionOrderCodes.add("ORDER-" + o.getOrderId());
                                sessionOrderCodes.add(String.valueOf(o.getOrderId()));
                            }
                        }
                    }
                }

                // 8. Vector Similarity Search trong kho tài liệu tĩnh (Phase 1)
                double threshold = aiProperties.getRag().getSimilarityThreshold();
                SearchRequest searchReq = SearchRequest.query(userMessage)
                        .withTopK(4)
                        .withSimilarityThreshold(threshold);

                List<Document> matchedDocs = vectorStore.similaritySearch(searchReq);

                // NẾU CẢ TÀI LIỆU, SẢN PHẨM VÀ ĐƠN HÀNG ĐỀU KHÔNG KHỚP -> KHÔNG GỌI LLM, GỬI TIN SYSTEM CHUYỂN NHÂN VIÊN
                if ((matchedDocs == null || matchedDocs.isEmpty()) && liveProducts.isEmpty() && !isProductIntent && !isOrderIntent) {
                    log.info("No documents matched similarityThreshold={} for query='{}'. Bypassing LLM and transferring to Human.",
                            threshold, userMessage);
                    sendTypingStatus(conversationId, false);
                    triggerHumanHandoff(conversationId, customer,
                            "Dạ câu hỏi này hiện nằm ngoài tài liệu chính sách của shop. Em xin phép chuyển cuộc trò chuyện sang nhân viên tư vấn để hỗ trợ bạn chính xác nhất nhé!",
                            "OUT_OF_DOMAIN");
                    return;
                }

                // 9. Chuẩn bị Context và Metadata Sources
                StringBuilder contextText = new StringBuilder();
                List<Map<String, Object>> sourcesList = new ArrayList<>();

                if (matchedDocs != null) {
                    for (Document doc : matchedDocs) {
                        contextText.append(doc.getContent()).append("\n\n");
                        Map<String, Object> src = new HashMap<>();
                        if (doc.getMetadata() != null) {
                            src.put("title", doc.getMetadata().get("title"));
                            src.put("category", doc.getMetadata().get("category"));
                            src.put("documentId", doc.getMetadata().get("documentId"));
                        }
                        sourcesList.add(src);
                    }
                }
                String sourcesJson = serializeSources(sourcesList);

                // Chuẩn bị Live Product Data cho Tool / RAG
                String liveProductText = formatLiveProductData(liveProducts);

                // 10. Lịch sử tin nhắn gần đây (tối đa 6 tin)
                String chatHistory = buildChatHistory(conversationId);

                // 11. Dựng prompt hoàn chỉnh từ template (bọc thẻ chống injection)
                String fullSystemPrompt = buildSystemPrompt(contextText.toString(), liveProductText, customerOrdersText, orderDetailsText, chatHistory, userMessage);
                GeminiGenerateRequest request = GeminiGenerateRequest.of(fullSystemPrompt, userMessage);

                // 12. Vòng lặp gọi Gemini API và Tool Calling (tối đa maxIterations vòng)
                GeminiGenerateResponse response = callGeminiWithRetry(chatModel, request, customer.getId(), userMessage);

                int maxIterations = aiProperties.getTools().getMaxIterations();
                int currentIteration = 0;

                while (currentIteration < maxIterations && response != null) {
                    GeminiGenerateResponse.FunctionCall fnCall = response.extractFunctionCall();
                    if (fnCall == null) {
                        fnCall = parsePromptToolCall(response.extractAnswerText());
                    }
                    if (fnCall == null) {
                        break; // Không còn tool call nào nữa, đây là câu trả lời cuối cùng
                    }

                    currentIteration++;
                    log.info("[AI TOOL CALL] Iteration {}/{}: calling tool '{}'", currentIteration, maxIterations, fnCall.getName());

                    // Thực thi Tool an toàn, ghi nhận orderCodes trả về cho phiên
                    String toolResult = executeTool(fnCall.getName(), fnCall.getArgs(), sessionOrderCodes);

                    // Kiểm tra limiter trước khi gọi lượt tiếp theo
                    GeminiRateLimiter.RateLimitResult nextRate = geminiRateLimiter.tryAcquireChat(chatModel, customer.getId(), userMessage);
                    if (!nextRate.isAllowed()) {
                        log.warn("[AI TOOL CALL] Quota limit reached during tool iteration, stopping loop");
                        break;
                    }

                    // Cập nhật prompt với kết quả tool và gọi lại Gemini
                    String updatedPrompt = fullSystemPrompt + "\n\n# KẾT QUẢ GỌI TOOL (" + fnCall.getName() + "):\n" + toolResult;
                    request = GeminiGenerateRequest.of(updatedPrompt, userMessage);
                    response = callGeminiWithRetry(chatModel, request, customer.getId(), userMessage);
                }

                sendTypingStatus(conversationId, false);

                if (response != null && !response.extractAnswerText().isBlank()) {
                    String rawAnswer = response.extractAnswerText();

                    // Kiểm tra cờ handoff từ mô hình
                    if (rawAnswer.contains("\"handoff\": true") || rawAnswer.contains("\"handoff\":true")) {
                        boolean genuineHandoff = isSensitiveTopicOrOrderMutation(userMessage)
                                || isHumanHandoffIntent(userMessage)
                                || rawAnswer.contains("SENSITIVE_TOPIC")
                                || rawAnswer.contains("CUSTOMER_REQUESTED");

                        if (!genuineHandoff && isProductAdvisoryIntent(userMessage)) {
                            log.warn("Suppressing false-positive model handoff during product advisory for query='{}'", userMessage);
                            rawAnswer = rawAnswer.replaceAll("\\{\\s*\"handoff\"\\s*:\\s*true[^}]*\\}", "").trim();
                        } else {
                            log.info("Model suggested human handoff for conversationId={}", conversationId);
                            String cleanNotice = rawAnswer.replaceAll("\\{\\s*\"handoff\"\\s*:\\s*true[^}]*\\}", "").trim();
                            if (cleanNotice.isBlank()) {
                                cleanNotice = "Dạ, em đã chuyển cuộc trò chuyện sang chuyên viên tư vấn SmashHub. Chuyên viên sẽ hỗ trợ bạn ngay ạ!";
                            }
                            triggerHumanHandoff(conversationId, customer, cleanNotice, "MODEL_REQUESTED");
                            return;
                        }
                    }

                    // NGUYÊN TẮC BẢO MẬT: OUTPUT VALIDATION GUARDRAIL
                    // Nếu câu trả lời nhắc đến mã đơn không nằm trong kết quả tool trả về của phiên đó -> chặn hiển thị & handoff ngay!
                    if (!validateOrderCodesInResponse(rawAnswer, sessionOrderCodes)) {
                        log.warn("[AI SECURITY ALERT] AI mentioned unauthorized or hallucinated order code! Blocking response and transferring to human.");
                        triggerHumanHandoff(conversationId, customer,
                                "Dạ em xin phép chuyển tiếp cuộc trò chuyện sang chuyên viên tư vấn để xác minh thông tin đơn hàng chính xác nhất cho bạn ạ!",
                                "POTENTIAL_UNAUTHORIZED_ORDER");
                        return;
                    }

                    // Theo dõi câu trả lời không chắc chắn liên tiếp (consecutive uncertain)
                    recordUncertaintyAndCheckHandoff(conversationId, customer, rawAnswer);

                    // Đối soát token & ghi nhận ngân sách
                    long promptTokens = 0L;
                    long candidateTokens = 0L;
                    if (response.getUsageMetadata() != null) {
                        promptTokens = response.getUsageMetadata().getPromptTokenCount();
                        candidateTokens = response.getUsageMetadata().getCandidatesTokenCount();
                        geminiRateLimiter.reconcileTokens(chatModel, estimatedTokens, promptTokens, candidateTokens);
                    }
                    aiBudgetTracker.recordUsage(chatModel, promptTokens, candidateTokens);

                    // 13. Phân tích kết quả gợi ý sản phẩm có cấu trúc (Structured Output)
                    ProductAdvisoryRecommendationDto recommendation = parseRecommendation(rawAnswer);
                    List<Long> recommendedIds = recommendation.getRecommendedProductIds();

                    if (recommendedIds != null && !recommendedIds.isEmpty()) {
                        // XÁC THỰC TỪNG ID CÓ THẬT, ACTIVE VÀ CÒN HÀNG (LIVE DB)
                        List<Product> validProducts = new ArrayList<>();
                        int maxCards = aiProperties.getRag().getMaxRecommendedProducts();

                        for (Long pid : recommendedIds) {
                            if (validProducts.size() >= maxCards) break;
                            Optional<Product> pOpt = productRepository.findById(pid);

                            if (pOpt.isEmpty()) {
                                log.warn("[AI PRODUCT ADVISORY] Rejected non-existent product recommendation id={}", pid);
                                continue;
                            }
                            Product prod = pOpt.get();
                            if (prod.getProductStatus() != ProductStatus.ACTIVE) {
                                log.warn("[AI PRODUCT ADVISORY] Rejected non-active product recommendation id={}", pid);
                                continue;
                            }

                            // Kiểm tra tồn kho thực tế
                            List<ProductVariant> variants = productVariantRepository.findByColorProductId(pid);
                            int stock = variants.stream()
                                    .filter(v -> v.getActive() == VariantStatus.ACTIVE)
                                    .mapToInt(v -> v.getStock() != null ? v.getStock() : 0)
                                    .sum();

                            if (stock <= 0) {
                                log.warn("[AI PRODUCT ADVISORY] Rejected out-of-stock product recommendation id={}", pid);
                                continue;
                            }

                            validProducts.add(prod);
                        }

                        // Gửi tin nhắn BOT giải thích
                        String textExplanation = recommendation.getText();
                        if (textExplanation == null || textExplanation.isBlank()) {
                            textExplanation = "Dạ theo yêu cầu của bạn, em gợi ý các mẫu sản phẩm phù hợp nhất dưới đây:";
                        }
                        sendBotResponse(conversationId, customer, textExplanation, sourcesJson, chatModel);

                        // Gửi từng thẻ PRODUCT_CARD cho các sản phẩm hợp lệ
                        for (Product p : validProducts) {
                            sendBotProductCard(conversationId, customer, p, chatModel);
                        }

                    } else {
                        // Không có thẻ sản phẩm -> gửi tin nhắn text thông thường
                        String cleanAnswer = recommendation.getText() != null && !recommendation.getText().isBlank()
                                ? recommendation.getText()
                                : rawAnswer;
                        sendBotResponse(conversationId, customer, cleanAnswer, sourcesJson, chatModel);

                        // Hỗ trợ gửi ORDER_CARD nếu khách yêu cầu hoặc câu trả lời đề cập đến đơn hàng duy nhất của khách
                        checkAndSendOrderCardIfRequested(conversationId, customer, userMessage, sessionOrderCodes, chatModel);
                    }

                } else {
                    executeFallback(conversationId, customer, userMessage, "GEMINI_UNAVAILABLE");
                }

            } catch (Exception ex) {
                log.error("Error processing AI message for conversationId={}", conversationId, ex);
                sendTypingStatus(conversationId, false);
                executeFallback(conversationId, customer, userMessage, "INTERNAL_ERROR");
            }
        } finally {
            // NGUYÊN TẮC BẢO MẬT GIAI ĐOẠN 3: Bắt buộc dọn sạch ThreadLocal
            AiChatSecurityContext.clear();
        }
    }

    private void rememberOrderCodes(Set<String> sessionOrderCodes, List<OrderSummaryDto> myOrders) {
        if (sessionOrderCodes == null || myOrders == null) return;
        for (OrderSummaryDto o : myOrders) {
            if (o.getOrderCode() != null) sessionOrderCodes.add(o.getOrderCode().toUpperCase());
            if (o.getOrderId() != null) {
                sessionOrderCodes.add("ORDER-" + o.getOrderId());
                sessionOrderCodes.add(String.valueOf(o.getOrderId()));
            }
        }
    }

    private String executeTool(String toolName, Map<String, Object> args) {
        return executeTool(toolName, args, new HashSet<>());
    }

    private String executeTool(String toolName, Map<String, Object> args, Set<String> sessionOrderCodes, boolean[] orderToolUsed) {
        if ("getMyOrders".equals(toolName) || "getMyOrderStatus".equals(toolName)) {
            if (orderToolUsed != null && orderToolUsed.length > 0) {
                orderToolUsed[0] = true;
            }
        }
        return executeTool(toolName, args, sessionOrderCodes);
    }

    private String executeTool(String toolName, Map<String, Object> args, Set<String> sessionOrderCodes) {
        if (args == null) args = Collections.emptyMap();
        try {
            switch (toolName) {
                case "searchProducts" -> {
                    String query = (String) args.get("query");
                    String category = (String) args.get("category");
                    String brand = (String) args.get("brand");
                    BigDecimal minPrice = args.get("minPrice") != null ? new BigDecimal(args.get("minPrice").toString()) : null;
                    BigDecimal maxPrice = args.get("maxPrice") != null ? new BigDecimal(args.get("maxPrice").toString()) : null;
                    Integer limit = args.get("limit") != null ? Integer.parseInt(args.get("limit").toString()) : 5;

                    List<ProductSearchResultDto> results = productTools.searchProducts(query, category, brand, minPrice, maxPrice, limit);
                    return objectMapper.writeValueAsString(results);
                }
                case "getProductDetail" -> {
                    Long pid = Long.parseLong(args.get("productId").toString());
                    ProductDetailDto detail = productTools.getProductDetail(pid);
                    return objectMapper.writeValueAsString(detail);
                }
                case "checkStock" -> {
                    Long pid = Long.parseLong(args.get("productId").toString());
                    ProductStockDto stock = productTools.checkStock(pid);
                    return objectMapper.writeValueAsString(stock);
                }
                case "getMyOrders" -> {
                    Integer limit = args.get("limit") != null ? Integer.parseInt(args.get("limit").toString()) : 3;
                    List<OrderSummaryDto> orders = orderTools.getMyOrders(limit);
                    if (orders != null && sessionOrderCodes != null) {
                        for (OrderSummaryDto o : orders) {
                            if (o.getOrderCode() != null) sessionOrderCodes.add(o.getOrderCode().toUpperCase());
                            if (o.getOrderId() != null) {
                                sessionOrderCodes.add("ORDER-" + o.getOrderId());
                                sessionOrderCodes.add(String.valueOf(o.getOrderId()));
                            }
                        }
                    }
                    return objectMapper.writeValueAsString(orders);
                }
                case "getMyOrderStatus" -> {
                    String orderCode = (String) args.get("orderCode");
                    OrderDetailDto detail = orderTools.getMyOrderStatus(orderCode);
                    if (detail != null) {
                        if (sessionOrderCodes != null) {
                            if (detail.getOrderCode() != null) sessionOrderCodes.add(detail.getOrderCode().toUpperCase());
                            if (detail.getOrderId() != null) {
                                sessionOrderCodes.add("ORDER-" + detail.getOrderId());
                                sessionOrderCodes.add(String.valueOf(detail.getOrderId()));
                            }
                            if (detail.getTrackingCode() != null) sessionOrderCodes.add(detail.getTrackingCode().toUpperCase());
                        }
                        return objectMapper.writeValueAsString(detail);
                    } else {
                        return "{\"status\": \"NOT_FOUND_OR_UNAUTHORIZED\", \"message\": \"Không tìm thấy đơn hàng hoặc đơn không thuộc về tài khoản này.\"}";
                    }
                }
                default -> {
                    log.warn("Unknown tool name: {}", toolName);
                    return "{}";
                }
            }
        } catch (Exception e) {
            log.error("Error executing tool {}: {}", toolName, e.getMessage());
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    private GeminiGenerateResponse.FunctionCall parsePromptToolCall(String text) {
        if (text == null) return null;
        Pattern pattern = Pattern.compile("\\{\\s*\"tool\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"arguments\"\\s*:\\s*(\\{[^}]*\\})\\s*\\}");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            String toolName = matcher.group(1);
            String argsJson = matcher.group(2);
            try {
                Map<String, Object> args = objectMapper.readValue(argsJson, new TypeReference<Map<String, Object>>() {});
                return new GeminiGenerateResponse.FunctionCall(toolName, args);
            } catch (Exception ignored) {}
        }
        return null;
    }

    public ProductAdvisoryRecommendationDto parseRecommendation(String rawAnswer) {
        if (rawAnswer == null || rawAnswer.isBlank()) {
            return new ProductAdvisoryRecommendationDto("", Collections.emptyList());
        }

        // 1. Thử parse trực tiếp JSON
        try {
            return objectMapper.readValue(rawAnswer.trim(), ProductAdvisoryRecommendationDto.class);
        } catch (Exception ignored) {}

        // 2. Tìm khối JSON trong markdown hoặc text
        Pattern pattern = Pattern.compile("\\{[\\s\\S]*?\"recommendedProductIds\"[\\s\\S]*?\\}");
        Matcher matcher = pattern.matcher(rawAnswer);
        if (matcher.find()) {
            try {
                String jsonStr = matcher.group();
                ProductAdvisoryRecommendationDto dto = objectMapper.readValue(jsonStr, ProductAdvisoryRecommendationDto.class);
                if (dto.getText() == null || dto.getText().isBlank()) {
                    String cleanText = rawAnswer.replace(jsonStr, "")
                            .replaceAll("```json", "")
                            .replaceAll("```", "")
                            .trim();
                    dto.setText(cleanText);
                }
                return dto;
            } catch (Exception ignored) {}
        }

        // 3. Fallback: không có JSON -> toàn bộ là văn bản trả lời
        return new ProductAdvisoryRecommendationDto(rawAnswer.trim(), Collections.emptyList());
    }

    @Transactional
    public void sendBotProductCard(Long conversationId, User customer, Product product, String modelName) {
        Conversation conversation = conversationRepository.findById(conversationId).orElse(null);
        if (conversation == null || product == null) return;

        User botUser = userRepository.findByUsername("system")
                .or(() -> userRepository.findById(1L))
                .orElse(customer);

        String thumbnail = resolveProductThumbnail(product.getId());
        ProductSummaryCardDto cardDto = ProductSummaryCardDto.builder()
                .id(product.getId())
                .name(product.getName())
                .slug(product.getSlug())
                .price(product.getPrice())
                .salePrice(product.getSalePrice())
                .thumbnailUrl(thumbnail)
                .build();

        String snapshotJson;
        try {
            snapshotJson = objectMapper.writeValueAsString(cardDto);
        } catch (JsonProcessingException e) {
            snapshotJson = null;
        }

        String cardPreview = "[Sản phẩm: " + product.getName() + "]";

        ChatMessage message = ChatMessage.builder()
                .conversation(conversation)
                .sender(botUser)
                .senderRole(SenderRole.BOT)
                .messageType(MessageType.PRODUCT_CARD)
                .referencedProductId(product.getId())
                .snapshotJson(snapshotJson)
                .content(cardPreview)
                .clientMessageId(UUID.randomUUID().toString())
                .aiModel(modelName)
                .isRead(false)
                .build();

        ChatMessage saved = chatMessageRepository.save(message);
        LocalDateTime now = LocalDateTime.now();
        conversationRepository.updateOnAiMessage(conversationId, safePreview(cardPreview), now);

        ChatMessageResponse response = chatMapper.toChatMessageResponse(saved);
        response.setSenderDisplayName("Trợ lý AI");
        response.setProductCard(cardDto);

        chatBroadcaster.broadcast("/topic/conversations/" + conversationId, response);

        conversationRepository.findWithDetailsById(conversationId).ifPresent(c -> {
            ConversationUpdateEvent updateEvent = chatMapper.toConversationUpdateEvent(c);
            chatBroadcaster.broadcast("/topic/admin/conversations", updateEvent);
        });
    }

    private GeminiGenerateResponse callGeminiWithRetry(String modelName, GeminiGenerateRequest request, Long userId, String userMessage) {
        GeminiGenerateResponse response = null;
        int maxAttempts = aiProperties.getRetry().getMaxAttempts();
        long backoff = aiProperties.getRetry().getBackoffMillis();

        for (int attempt = 0; attempt <= maxAttempts; attempt++) {
            try {
                if (attempt > 0) {
                    GeminiRateLimiter.RateLimitResult retryCheck = geminiRateLimiter.tryAcquireChat(modelName, userId, userMessage);
                    if (!retryCheck.isAllowed()) {
                        log.warn("Rate limiter rejected retry attempt #{}", attempt);
                        break;
                    }
                }
                response = geminiApiClient.generateContent(modelName, request);
                break;

            } catch (CallNotPermittedException cbEx) {
                log.warn("[CIRCUIT BREAKER OPEN] Gemini fast-fail");
                break;
            } catch (HttpClientErrorException.TooManyRequests ex429) {
                log.warn("[GEMINI 429] Rate limited on attempt #{}/{}", attempt + 1, maxAttempts + 1);
                if (attempt < maxAttempts) {
                    long sleepMs = backoff * (1L << attempt);
                    String retryAfterHeader = ex429.getResponseHeaders() != null ? ex429.getResponseHeaders().getFirst("Retry-After") : null;
                    if (retryAfterHeader != null) {
                        try { sleepMs = Long.parseLong(retryAfterHeader) * 1000L; } catch (NumberFormatException ignored) {}
                    }
                    try { Thread.sleep(sleepMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            } catch (HttpServerErrorException | ResourceAccessException netEx) {
                log.warn("[GEMINI NETWORK/SERVER ERROR] Attempt #{}: {}", attempt + 1, netEx.getMessage());
                if (attempt < maxAttempts) {
                    try { Thread.sleep(backoff * (1L << attempt)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            } catch (Exception fatalEx) {
                log.error("[GEMINI FATAL ERROR] Non-retryable error: {}", fatalEx.getMessage());
                break;
            }
        }
        return response;
    }

    @Transactional
    public void sendBotResponse(Long conversationId, User customer, String answerText, String sourcesJson, String modelName) {
        Conversation conversation = conversationRepository.findById(conversationId).orElse(null);
        if (conversation == null) return;

        User botUser = userRepository.findByUsername("system")
                .or(() -> userRepository.findById(1L))
                .orElse(customer);

        ChatMessage message = ChatMessage.builder()
                .conversation(conversation)
                .sender(botUser)
                .senderRole(SenderRole.BOT)
                .content(answerText)
                .messageType(MessageType.TEXT)
                .clientMessageId(UUID.randomUUID().toString())
                .aiModel(modelName)
                .aiSources(sourcesJson)
                .isRead(false)
                .build();

        ChatMessage saved = chatMessageRepository.save(message);
        LocalDateTime now = LocalDateTime.now();
        conversationRepository.updateOnAiMessage(conversationId, safePreview(answerText), now);

        ChatMessageResponse response = chatMapper.toChatMessageResponse(saved);
        response.setSenderDisplayName("Trợ lý AI");

        chatBroadcaster.broadcast("/topic/conversations/" + conversationId, response);

        conversationRepository.findWithDetailsById(conversationId).ifPresent(c -> {
            ConversationUpdateEvent updateEvent = chatMapper.toConversationUpdateEvent(c);
            chatBroadcaster.broadcast("/topic/admin/conversations", updateEvent);
        });

        if (meterRegistry != null) {
            meterRegistry.counter("smashhub.ai.messages.sent", "role", "BOT").increment();
        }
    }

    @Transactional
    public void triggerHumanHandoff(Long conversationId, User customer, String notice) {
        triggerHumanHandoff(conversationId, customer, notice, "CUSTOMER_REQUESTED");
    }

    @Transactional
    public void triggerHumanHandoff(Long conversationId, User customer, String notice, String handoffReason) {
        log.info("Switching conversationId={} to HUMAN mode, handoffReason={}", conversationId, handoffReason);

        conversationRepository.updateHandoffStatus(conversationId, ConversationStatus.OPEN, HandlerMode.HUMAN, handoffReason);

        User systemUser = userRepository.findByUsername("system")
                .or(() -> userRepository.findById(1L))
                .orElse(customer);

        // 1. Gửi tin nhắn thông báo cho khách hàng (visibleToCustomer = true)
        ChatMessage sysMsg = ChatMessage.builder()
                .conversation(conversationRepository.getReferenceById(conversationId))
                .sender(systemUser)
                .senderRole(SenderRole.SYSTEM)
                .content(notice)
                .messageType(MessageType.TEXT)
                .clientMessageId(UUID.randomUUID().toString())
                .visibleToCustomer(true)
                .isRead(false)
                .build();

        ChatMessage saved = chatMessageRepository.save(sysMsg);
        LocalDateTime now = LocalDateTime.now();
        conversationRepository.updateOnSystemMessage(conversationId, safePreview(notice), now);

        ChatMessageResponse response = chatMapper.toChatMessageResponse(saved);
        chatBroadcaster.broadcast("/topic/conversations/" + conversationId, response);

        // 2. Thu thập 5 tin nhắn gần nhất để làm tóm tắt cho Admin
        Page<ChatMessage> recentPage = chatMessageRepository.findByConversationId(
                conversationId, PageRequest.of(0, 5, Sort.by("createDate").descending()));
        List<ChatMessage> recentList = (recentPage != null && recentPage.hasContent())
                ? new ArrayList<>(recentPage.getContent())
                : Collections.emptyList();
        Collections.reverse(recentList);

        // 3. Sinh tóm tắt ngắn cho Admin qua LLM (hoặc template fallback)
        String adminSummary = generateAdminHandoffSummary(conversationId, customer, handoffReason, recentList);

        // 4. Lưu tin nhắn tóm tắt nội bộ cho Admin (visibleToCustomer = false)
        ChatMessage adminSummaryMsg = ChatMessage.builder()
                .conversation(conversationRepository.getReferenceById(conversationId))
                .sender(systemUser)
                .senderRole(SenderRole.SYSTEM)
                .content(adminSummary)
                .messageType(MessageType.TEXT)
                .clientMessageId(UUID.randomUUID().toString())
                .visibleToCustomer(false)
                .isRead(false)
                .build();

        ChatMessage savedAdminSummary = chatMessageRepository.save(adminSummaryMsg);
        ChatMessageResponse adminSummaryResponse = chatMapper.toChatMessageResponse(savedAdminSummary);

        // Broadcast tóm tắt CHỈ ĐẾN KÊNH ADMIN (Khách hàng không bao giờ nhận được)
        chatBroadcaster.broadcast("/topic/admin/conversations/" + conversationId, adminSummaryResponse);

        // 5. Broadcast sự kiện cập nhật hội thoại đến Admin kèm handoffReason
        conversationRepository.findWithDetailsById(conversationId).ifPresent(c -> {
            ConversationUpdateEvent updateEvent = chatMapper.toConversationUpdateEvent(c);
            chatBroadcaster.broadcast("/topic/admin/conversations", updateEvent);
        });
    }

    private String generateAdminHandoffSummary(Long conversationId, User customer, String handoffReason, List<ChatMessage> recentMessages) {
        try {
            String chatModel = aiProperties.getChatModel();
            GeminiRateLimiter.RateLimitResult rateCheck = geminiRateLimiter.tryAcquireChat(chatModel, 0L, "handoff-summary");
            if (rateCheck.isAllowed()) {
                StringBuilder hist = new StringBuilder();
                for (ChatMessage m : recentMessages) {
                    hist.append(m.getSenderRole()).append(": ").append(m.getContent()).append("\n");
                }

                String summaryPrompt = "Bạn là trợ lý hệ thống SmashHub. Hãy tóm tắt cuộc trò chuyện sau cho nhân viên CSKH (dưới 80 từ):\n"
                        + "- Lý do chuyển giao: " + handoffReason + "\n"
                        + "- Lịch sử trò chuyện gần nhất:\n" + hist + "\n"
                        + "Nêu ngắn gọn: Vấn đề chính của khách, thông tin đã có, sản phẩm/đơn hàng liên quan.";

                GeminiGenerateRequest req = GeminiGenerateRequest.of(summaryPrompt, "Tóm tắt cuộc trò chuyện cho nhân viên");
                GeminiGenerateResponse resp = geminiApiClient.generateContent(chatModel, req);
                if (resp != null && !resp.extractAnswerText().isBlank()) {
                    return "[BÀN GIAO CHO NHÂN VIÊN - TÓM TẮT TỰ ĐỘNG]\n" + resp.extractAnswerText().trim();
                }
            }
        } catch (Exception e) {
            log.warn("LLM summary generation failed for conversationId={}, falling back to template: {}",
                    conversationId, e.getMessage());
        }

        return buildFallbackTemplateSummary(customer, handoffReason, recentMessages);
    }

    private String buildFallbackTemplateSummary(User customer, String handoffReason, List<ChatMessage> recentMessages) {
        StringBuilder sb = new StringBuilder();
        sb.append("[BÀN GIAO CHO NHÂN VIÊN - TÓM TẮT TỰ ĐỘNG]\n");
        sb.append("• Khách hàng: ").append(customer.getUsername()).append("\n");
        sb.append("• Lý do bàn giao: ").append(handoffReason).append("\n");
        sb.append("• 5 tin nhắn gần nhất:\n");
        if (recentMessages != null) {
            for (ChatMessage m : recentMessages) {
                String roleStr = (m.getSenderRole() == SenderRole.CUSTOMER) ? "Khách" : (m.getSenderRole() == SenderRole.BOT ? "Bot" : "Hệ thống");
                sb.append("  - ").append(roleStr).append(": ").append(m.getContent()).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private void recordUncertaintyAndCheckHandoff(Long conversationId, User customer, String answerText) {
        if (answerText == null) return;
        boolean isUncertain = answerText.contains("\"uncertain\": true")
                || answerText.contains("\"uncertain\":true")
                || answerText.toLowerCase().contains("em không chắc")
                || answerText.toLowerCase().contains("không có đủ thông tin")
                || answerText.toLowerCase().contains("không thể khẳng định");

        if (redisTemplate == null) return;

        String key = "smashhub:ai:conv:" + conversationId + ":uncertain";
        try {
            if (isUncertain) {
                Long count = redisTemplate.opsForValue().increment(key);
                long ttl = aiProperties.getHandoff().getUncertainCacheTtlSeconds();
                redisTemplate.expire(key, Duration.ofSeconds(ttl));
                int threshold = aiProperties.getHandoff().getConsecutiveUncertainThreshold();
                if (count != null && count >= threshold) {
                    log.info("[AI HANDOFF] Consecutive uncertain responses threshold reached ({}/{}) for convId={}",
                            count, threshold, conversationId);
                    redisTemplate.delete(key);
                    triggerHumanHandoff(conversationId, customer,
                            "Dạ để hỗ trợ bạn chính xác và chu đáo nhất, em xin phép chuyển cuộc trò chuyện sang chuyên viên tư vấn SmashHub nhé ạ!",
                            "CONSECUTIVE_UNCERTAIN");
                }
            } else {
                redisTemplate.delete(key);
            }
        } catch (Exception e) {
            log.error("Redis error tracking uncertain responses", e);
        }
    }

    private boolean validateOrderCodesInResponse(String answerText, Set<String> sessionOrderCodes) {
        if (answerText == null || answerText.isBlank()) return true;

        Pattern orderPattern = Pattern.compile("(?i)\\bORDER-?(\\d+)\\b");
        Matcher matcher = orderPattern.matcher(answerText);
        while (matcher.find()) {
            String fullCode = matcher.group().toUpperCase();
            String numericId = matcher.group(1);
            boolean allowed = sessionOrderCodes.contains(fullCode)
                    || sessionOrderCodes.contains("ORDER-" + numericId)
                    || sessionOrderCodes.contains(numericId);
            if (!allowed) {
                log.warn("[SECURITY GUARDRAIL] Detected unauthorized or hallucinated order code: {} in answer. Allowed={}",
                        fullCode, sessionOrderCodes);
                return false;
            }
        }
        return true;
    }

    @Transactional
    public void sendBotOrderCard(Long conversationId, User customer, Long orderId, String modelName) {
        Conversation conversation = conversationRepository.findById(conversationId).orElse(null);
        if (conversation == null) return;

        // KIỂM QUYỀN SỞ HỮU NGHIÊM NGẶT: Chỉ được phép gửi thẻ đơn hàng của chính khách hàng này
        Optional<Order> orderOpt = orderRepository.findByIdAndUserIdWithDetails(orderId, customer.getId());
        if (orderOpt.isEmpty()) {
            log.warn("[AI ORDER CARD SECURITY] Attempted to create ORDER_CARD for unauthorized or non-existent orderId={} by customerId={}",
                    orderId, customer.getId());
            return;
        }

        Order order = orderOpt.get();
        OrderSummaryCardDto cardDto = OrderSummaryCardDto.builder()
                .id(order.getId())
                .orderStatus(order.getOrderStatus())
                .total(order.getTotal())
                .createdAt(order.getCreateDate())
                .itemsCount(order.getItems() != null ? order.getItems().size() : 0)
                .build();

        String snapshotJson;
        try {
            snapshotJson = objectMapper.writeValueAsString(cardDto);
        } catch (JsonProcessingException e) {
            snapshotJson = null;
        }

        String cardPreview = "[Đơn hàng: ORDER-" + order.getId() + "]";
        User botUser = userRepository.findByUsername("system")
                .or(() -> userRepository.findById(1L))
                .orElse(customer);

        ChatMessage message = ChatMessage.builder()
                .conversation(conversation)
                .sender(botUser)
                .senderRole(SenderRole.BOT)
                .messageType(MessageType.ORDER_CARD)
                .referencedOrderId(order.getId())
                .snapshotJson(snapshotJson)
                .content(cardPreview)
                .clientMessageId(UUID.randomUUID().toString())
                .aiModel(modelName)
                .visibleToCustomer(true)
                .isRead(false)
                .build();

        ChatMessage saved = chatMessageRepository.save(message);
        LocalDateTime now = LocalDateTime.now();
        conversationRepository.updateOnAiMessage(conversationId, safePreview(cardPreview), now);

        ChatMessageResponse response = chatMapper.toChatMessageResponse(saved);
        response.setSenderDisplayName("Trợ lý AI");
        response.setOrderCard(cardDto);

        chatBroadcaster.broadcast("/topic/conversations/" + conversationId, response);

        conversationRepository.findWithDetailsById(conversationId).ifPresent(c -> {
            ConversationUpdateEvent updateEvent = chatMapper.toConversationUpdateEvent(c);
            chatBroadcaster.broadcast("/topic/admin/conversations", updateEvent);
        });
    }

    private void checkAndSendOrderCardIfRequested(Long conversationId, User customer, String userMessage, Set<String> sessionOrderCodes, String modelName) {
        if (userMessage == null || sessionOrderCodes == null || sessionOrderCodes.isEmpty()) return;
        String lower = userMessage.toLowerCase();
        boolean askedForCard = lower.contains("thẻ đơn") || lower.contains("thẻ đơn hàng") || lower.contains("xem thẻ") || lower.contains("card");
        if (askedForCard) {
            for (String code : sessionOrderCodes) {
                try {
                    String clean = code.replace("ORDER-", "").replace("#", "").trim();
                    Long oid = Long.parseLong(clean);
                    sendBotOrderCard(conversationId, customer, oid, modelName);
                    break;
                } catch (NumberFormatException ignored) {}
            }
        }
    }

    public boolean isSensitiveTopicOrOrderMutation(String text) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase().trim();
        return lower.contains("hủy đơn")
                || lower.contains("huỷ đơn")
                || lower.contains("đổi địa chỉ")
                || lower.contains("sửa địa chỉ")
                || (lower.contains("địa chỉ") && (lower.contains("sửa") || lower.contains("đổi") || lower.contains("thay")))
                || (lower.contains("số điện thoại") && (lower.contains("sửa") || lower.contains("đổi") || lower.contains("thay")))
                || (lower.contains("sđt") && (lower.contains("sửa") || lower.contains("đổi") || lower.contains("thay")))
                || lower.contains("hoàn tiền")
                || lower.contains("trả hàng")
                || lower.contains("khiếu nại")
                || lower.contains("hàng lỗi")
                || lower.contains("hàng gãy")
                || lower.contains("bị gãy")
                || lower.contains("gãy cán")
                || lower.contains("hàng vỡ")
                || lower.contains("bị vỡ")
                || lower.contains("hàng hỏng")
                || lower.contains("bị hỏng")
                || lower.contains("hàng giả")
                || lower.contains("hàng nhái")
                || lower.contains("chất lượng kém")
                || lower.contains("lừa đảo")
                || lower.contains("chuyển khoản")
                || lower.contains("lỗi thanh toán")
                || lower.contains("trừ tiền")
                || lower.contains("sự cố thanh toán")
                || lower.contains("chưa nhận được tiền")
                || lower.contains("bị trừ tiền");
    }

    private boolean isOrderLookupIntent(String text) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase().trim();
        return lower.contains("đơn hàng")
                || lower.contains("mã đơn")
                || lower.contains("order-")
                || lower.contains("kiểm tra đơn")
                || lower.contains("tra cứu đơn")
                || lower.contains("đơn của tôi")
                || lower.contains("vận đơn")
                || lower.contains("giao hàng chưa")
                || lower.contains("bao giờ giao");
    }

    private String formatCustomerOrders(List<OrderSummaryDto> orders) {
        if (orders == null || orders.isEmpty()) {
            return "(Khách hàng hiện chưa có đơn hàng nào hoặc không tìm thấy đơn)";
        }
        StringBuilder sb = new StringBuilder();
        DecimalFormat df = new DecimalFormat("###,###,### VNĐ");
        for (OrderSummaryDto o : orders) {
            sb.append(String.format("- Mã đơn: %s | Ngày đặt: %s | Trạng thái: %s | Tổng tiền: %s | Vận chuyển: %s (%s)\n",
                    o.getOrderCode(), o.getOrderDate(), o.getOrderStatus(),
                    o.getTotalAmount() != null ? df.format(o.getTotalAmount()) : "0 đ",
                    o.getShippingStatus(), o.getCarrier()));
            if (o.getItems() != null) {
                for (OrderSummaryDto.OrderItemSummaryDto it : o.getItems()) {
                    sb.append(String.format("   + %s (x%d)\n", it.getProductName(), it.getQuantity()));
                }
            }
        }
        return sb.toString();
    }

    @Transactional
    public void executeFallback(Long conversationId, User customer, String userMessage, String reason) {
        log.info("[AI FALLBACK] convId={}, reason={}", conversationId, reason);

        if (meterRegistry != null) {
            meterRegistry.counter("smashhub.ai.fallback", "reason", reason).increment();
        }

        Optional<String> faq = aiFaqService.findFaqAnswer(userMessage);
        if (faq.isPresent()) {
            sendBotResponse(conversationId, customer, faq.get(), "[{\"title\": \"Câu hỏi thường gặp FAQ\", \"category\": \"FAQ\"}]", "faq-cache");
            return;
        }

        String fallbackNotice = "Trợ lý đang bận, đã chuyển yêu cầu cho nhân viên tư vấn SmashHub. Chuyên viên sẽ hỗ trợ bạn trong ít phút!";
        triggerHumanHandoff(conversationId, customer, fallbackNotice);
    }

    private void sendTypingStatus(Long conversationId, boolean isTyping) {
        try {
            TypingEvent event = new TypingEvent(conversationId, 0L, "Trợ lý AI", SenderRole.BOT, isTyping);
            chatBroadcaster.broadcast("/topic/conversations/" + conversationId + "/typing", event);
        } catch (Exception e) {
            log.debug("Failed to send typing event", e);
        }
    }

    public boolean isHumanHandoffIntent(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase().trim();
        return lower.contains("gặp nhân viên")
                || lower.contains("gặp tư vấn viên")
                || lower.contains("nói chuyện với người")
                || lower.contains("nói chuyện với nhân viên")
                || lower.contains("nói chuyện trực tiếp")
                || lower.contains("gặp người")
                || lower.contains("chuyển nhân viên")
                || lower.contains("chuyển admin")
                || lower.contains("chuyển máy cho admin")
                || lower.contains("gặp admin")
                || lower.contains("người thật")
                || lower.contains("human agent");
    }

    private boolean isProductAdvisoryIntent(String text) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase();
        return lower.contains("vợt") || lower.contains("giày") || lower.contains("cước")
                || lower.contains("balo") || lower.contains("túi") || lower.contains("áo")
                || lower.contains("tư vấn") || lower.contains("gợi ý") || lower.contains("tìm")
                || lower.contains("mua") || lower.contains("giá") || lower.contains("tầm")
                || lower.contains("triệu") || lower.contains("đánh đôi") || lower.contains("tấn công")
                || lower.contains("phản tạt") || lower.contains("công thủ") || lower.contains("bè")
                || lower.contains("yonex") || lower.contains("lining") || lower.contains("victor")
                || lower.contains("mizuno") || lower.contains("astrox") || lower.contains("nanoflare")
                || lower.contains("arcsaber") || lower.contains("axforce") || lower.contains("halbertec");
    }

    boolean isJunkOrShortMessage(String text) {
        if (text == null) return true;
        String clean = text.trim().toLowerCase();
        if (clean.length() < 3) return true;

        if (clean.equals("alo") || clean.equals("hey") || clean.equals("abc") || clean.equals("xyz")
                || clean.equals("zzz") || clean.equals("aaa") || clean.equals("bbb") || clean.equals("ccc")
                || clean.equals("ddd") || clean.equals("eee") || clean.equals("123") || clean.equals("asdf")
                || clean.equals("...") || clean.equals("???") || clean.equals("!!!")) {
            return true;
        }

        if (clean.equals("xin chào") || clean.equals("chào shop") || clean.equals("hello")
                || clean.equals("hi shop") || clean.equals("chào bạn") || clean.equals("chào em")
                || clean.equals("shop ơi") || clean.equals("ad ơi") || clean.equals("alo shop")) {
            return true;
        }

        return clean.matches("^(.)\\1{2,}$");
    }

    boolean isMeaninglessGibberish(String clean) {
        if (clean == null || clean.isBlank()) return true;
        if (clean.equals("dd") || clean.equals("abc") || clean.equals("xyz")
                || clean.equals("zzz") || clean.equals("aaa") || clean.equals("bbb")
                || clean.equals("ccc") || clean.equals("ddd") || clean.equals("123")
                || clean.equals("asdf") || clean.matches("^(.)\\1{1,}$")) {
            return true;
        }
        return clean.length() <= 2 && !clean.equals("hi") && !clean.equals("ok") && !clean.equals("dạ") && !clean.equals("da");
    }

    boolean isBroadAdvisoryKeyword(String text) {
        if (text == null || text.isBlank()) return false;
        String clean = text.trim().toLowerCase()
                .replaceAll("[?!.,;:~]", "")
                .replaceAll("\\s+", " ");
        return clean.equals("tư vấn vợt")
                || clean.equals("mua vợt")
                || clean.equals("chọn vợt")
                || clean.equals("cần tư vấn vợt")
                || clean.equals("tư vấn mua vợt")
                || clean.equals("tư vấn vợt cầu lông")
                || clean.equals("mua vợt cầu lông")
                || clean.equals("chọn vợt cầu lông")
                || clean.equals("tư vấn vợt cho mình")
                || clean.equals("tư vấn giúp mình cây vợt")
                || clean.equals("tư vấn cho mình")
                || clean.equals("tư vấn giúp mình")
                || clean.equals("tư vấn")
                || clean.equals("cần tư vấn")
                || clean.equals("tư vấn bán hàng");
    }

    boolean isBroadShoeAdvisoryKeyword(String text) {
        if (text == null || text.isBlank()) return false;
        String clean = text.trim().toLowerCase()
                .replaceAll("[?!.,;:~]", "")
                .replaceAll("\\s+", " ");
        return clean.equals("tư vấn giày")
                || clean.equals("mua giày")
                || clean.equals("chọn giày")
                || clean.equals("tư vấn chọn giày")
                || clean.equals("tư vấn giày cầu lông")
                || clean.equals("chọn giày cầu lông")
                || clean.equals("mua giày cầu lông")
                || clean.equals("tư vấn giày cho mình");
    }

    private void resetGibberishCounter(Long conversationId) {
        if (redisTemplate != null) {
            try {
                redisTemplate.delete("chat:gibberish:conv:" + conversationId);
            } catch (Exception ignored) {}
        }
    }

    private String formatLiveProductData(List<ProductSearchResultDto> products) {
        if (products == null || products.isEmpty()) {
            return "(Hiện tại hệ thống không tìm thấy sản phẩm nào khớp hoàn toàn với tiêu chí trên)";
        }
        StringBuilder sb = new StringBuilder();
        DecimalFormat df = new DecimalFormat("###,###,### VNĐ");
        for (ProductSearchResultDto p : products) {
            BigDecimal effectivePrice = p.getSalePrice() != null ? p.getSalePrice() : p.getPrice();
            sb.append(String.format("- ID: %d | Tên: %s | Thương hiệu: %s | Danh mục: %s | Giá: %s | Tồn kho: %d cây (Còn hàng)",
                    p.getId(), p.getName(), p.getBrand(), p.getCategory(), df.format(effectivePrice), p.getStock()));
            if (p.getKeyAttributes() != null && !p.getKeyAttributes().isEmpty()) {
                sb.append(" | Thông số: ");
                p.getKeyAttributes().forEach((k, v) -> sb.append(k).append("=").append(v).append("; "));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String buildChatHistory(Long conversationId) {
        Page<ChatMessage> page = chatMessageRepository.findByConversationId(
                conversationId, PageRequest.of(0, 6, Sort.by("createDate").descending()));
        if (page == null || !page.hasContent()) {
            return "";
        }
        List<ChatMessage> list = new ArrayList<>(page.getContent());
        Collections.reverse(list);

        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : list) {
            String roleName = (msg.getSenderRole() == SenderRole.CUSTOMER) ? "Khách hàng" : "Trợ lý AI";
            sb.append(roleName).append(": ").append(msg.getContent()).append("\n");
        }
        return sb.toString();
    }

    private String buildSystemPrompt(String context, String liveProductData, String customerOrders, String orderDetails, String chatHistory, String userQuestion) {
        String tmpl = (this.systemPromptTemplate != null) ? this.systemPromptTemplate : fallbackPromptTemplate();
        return tmpl.replace("{context}", context != null ? context : "")
                .replace("{live_product_data}", liveProductData != null ? liveProductData : "")
                .replace("{customer_orders}", customerOrders != null ? customerOrders : "")
                .replace("{order_details}", orderDetails != null ? orderDetails : "")
                .replace("{chat_history}", chatHistory != null ? chatHistory : "")
                .replace("{user_question}", userQuestion != null ? userQuestion : "");
    }

    private String buildSystemPrompt(String context, String liveProductData, String chatHistory, String userQuestion) {
        return buildSystemPrompt(context, liveProductData, "", "", chatHistory, userQuestion);
    }

    private String serializeSources(List<Map<String, Object>> sources) {
        if (sources == null || sources.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(sources);
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveProductThumbnail(Long productId) {
        List<ProductImage> images = productImageRepository.findByColorProductIdOrderByOrderIndexAsc(productId);
        if (images.isEmpty()) return null;
        return images.stream()
                .filter(i -> i.getIsMain() == ImagePriority.MAIN)
                .findFirst()
                .or(() -> images.stream().findFirst())
                .map(ProductImage::getUrl)
                .orElse(null);
    }

    private String fallbackPromptTemplate() {
        return "Bạn là Trợ lý AI SmashHub. Hãy trả lời câu hỏi dựa trên ngữ cảnh được cung cấp.\n"
                + "<context>\n{context}\n</context>\n"
                + "<live_product_data>\n{live_product_data}\n</live_product_data>\n"
                + "<customer_orders>\n{customer_orders}\n</customer_orders>\n"
                + "<order_details>\n{order_details}\n</order_details>\n"
                + "<chat_history>\n{chat_history}\n</chat_history>\n"
                + "<user_question>\n{user_question}\n</user_question>";
    }

    private String safePreview(String text) {
        if (text == null) return "";
        return text.length() > 250 ? text.substring(0, 247) + "..." : text;
    }
}