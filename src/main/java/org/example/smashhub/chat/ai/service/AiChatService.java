package org.example.smashhub.chat.ai.service;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.example.smashhub.chat.dto.event.ConversationUpdateEvent;
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
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

@Service
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AiChatService {

    final GeminiApiClient geminiApiClient;
    final GeminiRateLimiter geminiRateLimiter;
    final AiBudgetTracker aiBudgetTracker;
    final AiKillSwitch aiKillSwitch;
    final AiFaqService aiFaqService;
    final AiCatalogRagService aiCatalogRagService;
    final ConversationRepository conversationRepository;
    final ChatMessageRepository chatMessageRepository;
    final UserRepository userRepository;
    final ChatBroadcaster chatBroadcaster;
    final ChatMapper chatMapper;
    final AiProperties aiProperties;

    @Autowired
    @Qualifier("geminiTaskExecutor")
    ThreadPoolTaskExecutor geminiTaskExecutor;

    public void setGeminiTaskExecutor(ThreadPoolTaskExecutor geminiTaskExecutor) {
        this.geminiTaskExecutor = geminiTaskExecutor;
    }

    @Autowired(required = false)
    MeterRegistry meterRegistry;

    /**
     * Điểm tiếp nhận tin nhắn khách hàng gửi tới AI Assistant.
     * Chạy bất đồng bộ trên thread pool chuyên biệt geminiTaskExecutor.
     */
    public void handleCustomerMessageAsync(Long conversationId, User customer, String userMessage) {
        try {
            geminiTaskExecutor.execute(() -> processCustomerMessage(conversationId, customer, userMessage));
        } catch (RejectedExecutionException rex) {
            log.warn("[AI THREAD POOL FULL] Queue capacity exceeded for conversationId={}. Triggering immediate fallback.", conversationId);
            executeFallback(conversationId, customer, userMessage, "QUEUE_FULL");
        }
    }

    private void processCustomerMessage(Long conversationId, User customer, String userMessage) {
        String modelName = aiProperties.getChatModel();

        // 1. Kiểm tra Kill Switch
        if (!aiKillSwitch.isAiEnabled()) {
            log.info("AI Assistant is currently disabled by Kill Switch. Routing conversationId={} to Human.", conversationId);
            executeFallback(conversationId, customer, userMessage, "KILL_SWITCH_DISABLED");
            return;
        }

        // 2. Kiểm tra Ngân sách hôm nay
        AiBudgetTracker.BudgetCheckResult budgetCheck = aiBudgetTracker.checkDailyBudget();
        if (!budgetCheck.isAllowed()) {
            log.warn("Daily AI budget exceeded ({}%). Disabling AI and routing conversationId={} to Human.",
                    budgetCheck.getPercent(), conversationId);
            executeFallback(conversationId, customer, userMessage, "BUDGET_EXCEEDED");
            return;
        }

        // 3. Tra cứu cache FAQ đã chuẩn hóa (tiết kiệm 100% chi phí & quota nếu trúng FAQ)
        Optional<String> faqAnswer = aiFaqService.findFaqAnswer(userMessage);
        if (faqAnswer.isPresent()) {
            log.info("FAQ Cache hit for conversationId={}. Delivering cached answer directly.", conversationId);
            sendAiResponse(conversationId, customer, faqAnswer.get());
            return;
        }

        // 4. Kiểm tra giới hạn Quota qua Redis Lua (User RPM/RPD + Model RPM/TPM/RPD)
        GeminiRateLimiter.RateLimitResult rateCheck = geminiRateLimiter.tryAcquireChat(modelName, customer.getId(), userMessage);
        if (!rateCheck.isAllowed()) {
            log.warn("Rate limit breached for conversationId={} (reason={}). Routing to fallback.", conversationId, rateCheck.getReason());
            if (meterRegistry != null) {
                meterRegistry.counter("smashhub.ai.rate_limited", "reason", rateCheck.getReason()).increment();
            }
            executeFallback(conversationId, customer, userMessage, rateCheck.getReason());
            return;
        }

        long estimatedTokens = rateCheck.getEstimatedTokens();

        // 5. Chuẩn bị prompt với RAG catalog/đơn hàng
        String systemPrompt = aiCatalogRagService.buildSystemPrompt(customer, userMessage);
        GeminiGenerateRequest request = GeminiGenerateRequest.of(systemPrompt, userMessage);

        // 6. Thực thi lời gọi Gemini với Retry có kiểm soát
        GeminiGenerateResponse response = null;
        int maxAttempts = aiProperties.getRetry().getMaxAttempts();
        long backoff = aiProperties.getRetry().getBackoffMillis();

        for (int attempt = 0; attempt <= maxAttempts; attempt++) {
            try {
                if (attempt > 0) {
                    // MỖI LẦN RETRY ĐỀU PHẢI QUA LIMITER (retry cũng tiêu tốn quota project)
                    GeminiRateLimiter.RateLimitResult retryLimit = geminiRateLimiter.tryAcquireChat(modelName, customer.getId(), userMessage);
                    if (!retryLimit.isAllowed()) {
                        log.warn("Cannot retry Gemini call: rate limiter rejected retry attempt #{}", attempt);
                        break;
                    }
                }

                response = geminiApiClient.generateContent(modelName, request);
                break; // Thành công

            } catch (CallNotPermittedException cbEx) {
                // Circuit Breaker đang MỞ -> Ngắt ngay lập tức, không thử lại
                log.warn("[CIRCUIT BREAKER OPEN] Gemini call fast-failed for conversationId={}", conversationId);
                if (meterRegistry != null) {
                    meterRegistry.counter("smashhub.ai.circuit_breaker.fast_fail").increment();
                }
                break;

            } catch (HttpClientErrorException.TooManyRequests ex429) {
                log.warn("[GEMINI 429 RESOURCE_EXHAUSTED] Attempt #{}/{}", attempt + 1, maxAttempts + 1);
                if (attempt < maxAttempts) {
                    long sleepMs = backoff * (1L << attempt);
                    // Kiểm tra header Retry-After nếu có
                    String retryAfterHeader = ex429.getResponseHeaders() != null
                            ? ex429.getResponseHeaders().getFirst("Retry-After")
                            : null;
                    if (retryAfterHeader != null) {
                        try {
                            sleepMs = Long.parseLong(retryAfterHeader) * 1000L;
                        } catch (NumberFormatException ignored) {}
                    }
                    try {
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (HttpServerErrorException | ResourceAccessException networkEx) {
                log.warn("[GEMINI SERVER/NETWORK ERROR] Attempt #{}: {}", attempt + 1, networkEx.getMessage());
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(backoff * (1L << attempt));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (Exception fatalEx) {
                // 400, 401, 403 hoặc lỗi khác -> KHÔNG RETRY
                log.error("[GEMINI FATAL ERROR] Non-retryable error: {}", fatalEx.getMessage());
                break;
            }
        }

        // 7. Xử lý kết quả trả về hoặc Fallback
        if (response != null && !response.extractAnswerText().isBlank()) {
            String answer = response.extractAnswerText();
            long promptTokens = 0L;
            long candidateTokens = 0L;

            if (response.getUsageMetadata() != null) {
                promptTokens = response.getUsageMetadata().getPromptTokenCount();
                candidateTokens = response.getUsageMetadata().getCandidatesTokenCount();
                // Đối soát lại số token thực tế với số ước lượng trong limiter
                geminiRateLimiter.reconcileTokens(modelName, estimatedTokens, promptTokens, candidateTokens);
            }

            // Ghi nhận ngân sách & chi phí
            aiBudgetTracker.recordUsage(modelName, promptTokens, candidateTokens);

            // Gửi câu trả lời của AI vào hội thoại
            sendAiResponse(conversationId, customer, answer);

        } else {
            // Không thành công -> Kích hoạt Fallback
            executeFallback(conversationId, customer, userMessage, "GEMINI_UNAVAILABLE");
        }
    }

    /**
     * Gửi tin nhắn trả lời của AI vào hội thoại và thông báo qua WebSocket STOMP.
     */
    @Transactional
    public void sendAiResponse(Long conversationId, User customer, String answerText) {
        Conversation conversation = conversationRepository.findById(conversationId).orElse(null);
        if (conversation == null) return;

        User aiUser = userRepository.findByUsername("system")
                .or(() -> userRepository.findById(1L))
                .orElse(customer);

        ChatMessage message = ChatMessage.builder()
                .conversation(conversation)
                .sender(aiUser)
                .senderRole(SenderRole.AI)
                .content(answerText)
                .messageType(MessageType.TEXT)
                .clientMessageId(UUID.randomUUID().toString())
                .isRead(false)
                .build();

        ChatMessage saved = chatMessageRepository.save(message);
        LocalDateTime now = LocalDateTime.now();
        conversationRepository.updateOnAiMessage(conversationId, answerText, now);

        ChatMessageResponse response = chatMapper.toChatMessageResponse(saved);
        response.setSenderUsername("Trợ lý AI SmashHub");

        // Broadcast tới kênh riêng của hội thoại
        chatBroadcaster.broadcast("/topic/conversations/" + conversationId, response);

        // Broadcast cập nhật tới admin dashboard
        conversationRepository.findWithDetailsById(conversationId).ifPresent(c -> {
            ConversationUpdateEvent updateEvent = chatMapper.toConversationUpdateEvent(c);
            chatBroadcaster.broadcast("/topic/admin/conversations", updateEvent);
        });

        if (meterRegistry != null) {
            meterRegistry.counter("smashhub.ai.messages.sent").increment();
        }
    }

    /**
     * FALLBACK CÓ CHỦ ĐÍCH KHI BỊ GIỚI HẠN HOẶC LỖI:
     * (a) Tra cứu FAQ nếu có.
     * (b) Nếu không, gửi tin nhắn SYSTEM "Trợ lý đang bận, đã chuyển yêu cầu cho nhân viên",
     *     đổi handlerMode = HUMAN, báo admin. Không bao giờ im lặng.
     */
    @Transactional
    public void executeFallback(Long conversationId, User customer, String userMessage, String reason) {
        log.info("[AI FALLBACK TRIGGERED] convId={}, reason={}", conversationId, reason);

        if (meterRegistry != null) {
            meterRegistry.counter("smashhub.ai.fallback", "reason", reason).increment();
        }

        Conversation conversation = conversationRepository.findById(conversationId).orElse(null);
        if (conversation == null) return;

        // (a) Tra cứu FAQ lần cuối trước khi chuyển giao nhân viên
        Optional<String> faq = aiFaqService.findFaqAnswer(userMessage);
        if (faq.isPresent()) {
            sendAiResponse(conversationId, customer, faq.get());
            return;
        }

        // (b) Chuyển handlerMode = HUMAN
        conversationRepository.updateHandlerMode(conversationId, HandlerMode.HUMAN);

        User systemUser = userRepository.findByUsername("system")
                .or(() -> userRepository.findById(1L))
                .orElse(customer);

        String fallbackNotice = "Trợ lý đang bận, đã chuyển yêu cầu cho nhân viên tư vấn SmashHub. Chuyên viên sẽ hỗ trợ bạn trong ít phút!";

        ChatMessage sysMsg = ChatMessage.builder()
                .conversation(conversation)
                .sender(systemUser)
                .senderRole(SenderRole.SYSTEM)
                .content(fallbackNotice)
                .messageType(MessageType.TEXT)
                .clientMessageId(UUID.randomUUID().toString())
                .isRead(false)
                .build();

        ChatMessage saved = chatMessageRepository.save(sysMsg);
        LocalDateTime now = LocalDateTime.now();
        conversationRepository.updateOnSystemMessage(conversationId, fallbackNotice, now);

        ChatMessageResponse response = chatMapper.toChatMessageResponse(saved);
        chatBroadcaster.broadcast("/topic/conversations/" + conversationId, response);

        // Báo cho Admin Dashboard
        conversationRepository.findWithDetailsById(conversationId).ifPresent(c -> {
            ConversationUpdateEvent updateEvent = chatMapper.toConversationUpdateEvent(c);
            chatBroadcaster.broadcast("/topic/admin/conversations", updateEvent);
        });
    }
}
