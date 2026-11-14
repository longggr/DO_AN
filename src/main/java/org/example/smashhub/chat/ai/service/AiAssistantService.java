package org.example.smashhub.chat.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
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
import org.example.smashhub.chat.dto.event.ConversationUpdateEvent;
import org.example.smashhub.chat.dto.event.TypingEvent;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;

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

    @Autowired
    @Qualifier("geminiTaskExecutor")
    ThreadPoolTaskExecutor geminiTaskExecutor;

    public void setGeminiTaskExecutor(ThreadPoolTaskExecutor geminiTaskExecutor) {
        this.geminiTaskExecutor = geminiTaskExecutor;
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

        // 2. Nhận diện ý định gặp nhân viên bằng từ khoá nhanh
        if (isHumanHandoffIntent(userMessage)) {
            log.info("Direct human handoff requested by customer in conversationId={}", conversationId);
            triggerHumanHandoff(conversationId, customer, "Dạ, em đã chuyển cuộc trò chuyện sang chuyên viên tư vấn SmashHub. Chuyên viên sẽ hỗ trợ bạn ngay ạ!");
            return;
        }

        // 3. Kiểm tra Ngân sách hôm nay
        AiBudgetTracker.BudgetCheckResult budgetCheck = aiBudgetTracker.checkDailyBudget();
        if (!budgetCheck.isAllowed()) {
            log.warn("Daily AI budget exceeded. Disabling AI for conversationId={}", conversationId);
            executeFallback(conversationId, customer, userMessage, "BUDGET_EXCEEDED");
            return;
        }

        // 4. Kiểm tra Cache FAQ đã chuẩn hoá
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
            // 7. Vector Similarity Search trong kho tài liệu
            double threshold = aiProperties.getRag().getSimilarityThreshold();
            SearchRequest searchReq = SearchRequest.query(userMessage)
                    .withTopK(4)
                    .withSimilarityThreshold(threshold);

            List<Document> matchedDocs = vectorStore.similaritySearch(searchReq);

            // NẾU KHÔNG CÓ ĐOẠN NÀO ĐẠT NGƯỠNG -> KHÔNG GỌI LLM, GỬI TIN SYSTEM ĐỀ NGHỊ CHUYỂN NHÂN VIÊN
            if (matchedDocs == null || matchedDocs.isEmpty()) {
                log.info("No documents matched similarityThreshold={} for query='{}'. Bypassing LLM and transferring to Human.",
                        threshold, userMessage);
                sendTypingStatus(conversationId, false);
                triggerHumanHandoff(conversationId, customer,
                        "Dạ câu hỏi này hiện nằm ngoài tài liệu chính sách của shop. Em xin phép chuyển cuộc trò chuyện sang nhân viên tư vấn để hỗ trợ bạn chính xác nhất nhé!");
                return;
            }

            // 8. Chuẩn bị Context và Metadata Sources
            StringBuilder contextText = new StringBuilder();
            List<Map<String, Object>> sourcesList = new ArrayList<>();

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
            String sourcesJson = serializeSources(sourcesList);

            // 9. Lịch sử tin nhắn gần đây (tối đa 6 tin)
            String chatHistory = buildChatHistory(conversationId);

            // 10. Dựng prompt hoàn chỉnh từ template
            String fullSystemPrompt = buildSystemPrompt(contextText.toString(), chatHistory, userMessage);
            GeminiGenerateRequest request = GeminiGenerateRequest.of(fullSystemPrompt, userMessage);

            // 11. Gọi Gemini API với Retry có kiểm soát
            GeminiGenerateResponse response = callGeminiWithRetry(chatModel, request, customer.getId(), userMessage);

            sendTypingStatus(conversationId, false);

            if (response != null && !response.extractAnswerText().isBlank()) {
                String rawAnswer = response.extractAnswerText();

                // Kiểm tra cờ handoff từ mô hình
                if (rawAnswer.contains("\"handoff\": true") || rawAnswer.contains("\"handoff\":true")) {
                    log.info("Model suggested human handoff for conversationId={}", conversationId);
                    String cleanNotice = rawAnswer.replaceAll("\\{\\s*\"handoff\"\\s*:\\s*true[^}]*\\}", "").trim();
                    if (cleanNotice.isBlank()) {
                        cleanNotice = "Dạ, em đã chuyển cuộc trò chuyện sang chuyên viên tư vấn SmashHub. Chuyên viên sẽ hỗ trợ bạn ngay ạ!";
                    }
                    triggerHumanHandoff(conversationId, customer, cleanNotice);
                    return;
                }

                // Đối soát token & ghi nhận ngân sách
                long promptTokens = 0L;
                long candidateTokens = 0L;
                if (response.getUsageMetadata() != null) {
                    promptTokens = response.getUsageMetadata().getPromptTokenCount();
                    candidateTokens = response.getUsageMetadata().getCandidatesTokenCount();
                    geminiRateLimiter.reconcileTokens(chatModel, estimatedTokens, promptTokens, candidateTokens);
                }
                aiBudgetTracker.recordUsage(chatModel, promptTokens, candidateTokens);

                // Lưu và broadcast tin nhắn BOT
                sendBotResponse(conversationId, customer, rawAnswer, sourcesJson, chatModel);

            } else {
                executeFallback(conversationId, customer, userMessage, "GEMINI_UNAVAILABLE");
            }

        } catch (Exception ex) {
            log.error("Error processing AI message for conversationId={}", conversationId, ex);
            sendTypingStatus(conversationId, false);
            executeFallback(conversationId, customer, userMessage, "INTERNAL_ERROR");
        }
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
        conversationRepository.updateOnAiMessage(conversationId, answerText, now);

        ChatMessageResponse response = chatMapper.toChatMessageResponse(saved);
        response.setSenderDisplayName("Trợ lý AI");

        // Broadcast tới kênh riêng của cuộc trò chuyện
        chatBroadcaster.broadcast("/topic/conversations/" + conversationId, response);

        // Broadcast cập nhật tới Admin Dashboard
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
        log.info("Switching conversationId={} to HUMAN mode and notifying staff", conversationId);

        // Đổi handlerMode = HUMAN, status = OPEN
        conversationRepository.updateHandlerMode(conversationId, HandlerMode.HUMAN);
        conversationRepository.updateStatus(conversationId, ConversationStatus.OPEN);

        User systemUser = userRepository.findByUsername("system")
                .or(() -> userRepository.findById(1L))
                .orElse(customer);

        ChatMessage sysMsg = ChatMessage.builder()
                .conversation(conversationRepository.getReferenceById(conversationId))
                .sender(systemUser)
                .senderRole(SenderRole.SYSTEM)
                .content(notice)
                .messageType(MessageType.TEXT)
                .clientMessageId(UUID.randomUUID().toString())
                .isRead(false)
                .build();

        ChatMessage saved = chatMessageRepository.save(sysMsg);
        LocalDateTime now = LocalDateTime.now();
        conversationRepository.updateOnSystemMessage(conversationId, notice, now);

        ChatMessageResponse response = chatMapper.toChatMessageResponse(saved);
        chatBroadcaster.broadcast("/topic/conversations/" + conversationId, response);

        conversationRepository.findWithDetailsById(conversationId).ifPresent(c -> {
            ConversationUpdateEvent updateEvent = chatMapper.toConversationUpdateEvent(c);
            chatBroadcaster.broadcast("/topic/admin/conversations", updateEvent);
        });
    }

    @Transactional
    public void executeFallback(Long conversationId, User customer, String userMessage, String reason) {
        log.info("[AI FALLBACK] convId={}, reason={}", conversationId, reason);

        if (meterRegistry != null) {
            meterRegistry.counter("smashhub.ai.fallback", "reason", reason).increment();
        }

        // Kiểm tra FAQ lần cuối trước khi chuyển nhân viên
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

    private boolean isHumanHandoffIntent(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase().trim();
        return lower.contains("gặp nhân viên")
                || lower.contains("gặp tư vấn viên")
                || lower.contains("nói chuyện với người")
                || lower.contains("gặp người")
                || lower.contains("chuyển nhân viên")
                || lower.contains("gặp admin")
                || lower.contains("người thật")
                || lower.contains("human agent");
    }

    private String buildChatHistory(Long conversationId) {
        Page<ChatMessage> page = chatMessageRepository.findByConversationId(
                conversationId, PageRequest.of(0, 6, Sort.by("createDate").descending()));
        List<ChatMessage> list = new ArrayList<>(page.getContent());
        Collections.reverse(list);

        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : list) {
            String roleName = (msg.getSenderRole() == SenderRole.CUSTOMER) ? "Khách hàng" : "Trợ lý AI";
            sb.append(roleName).append(": ").append(msg.getContent()).append("\n");
        }
        return sb.toString();
    }

    private String buildSystemPrompt(String context, String chatHistory, String userQuestion) {
        String tmpl = (this.systemPromptTemplate != null) ? this.systemPromptTemplate : fallbackPromptTemplate();
        return tmpl.replace("{context}", context != null ? context : "")
                .replace("{chat_history}", chatHistory != null ? chatHistory : "")
                .replace("{user_question}", userQuestion != null ? userQuestion : "");
    }

    private String serializeSources(List<Map<String, Object>> sources) {
        if (sources == null || sources.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(sources);
        } catch (Exception e) {
            return null;
        }
    }

    private String fallbackPromptTemplate() {
        return "Bạn là Trợ lý AI SmashHub. Hãy trả lời câu hỏi dựa trên ngữ cảnh được cung cấp.\n"
                + "<context>\n{context}\n</context>\n"
                + "<chat_history>\n{chat_history}\n</chat_history>\n"
                + "<user_question>\n{user_question}\n</user_question>";
    }
}

