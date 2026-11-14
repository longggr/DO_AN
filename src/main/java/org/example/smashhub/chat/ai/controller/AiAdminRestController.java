package org.example.smashhub.chat.ai.controller;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.example.smashhub.chat.ai.config.AiProperties;
import org.example.smashhub.chat.ai.config.AiResilienceConfig;
import org.example.smashhub.chat.ai.entity.AiFaqEntry;
import org.example.smashhub.chat.ai.limiter.AiBudgetTracker;
import org.example.smashhub.chat.ai.limiter.AiKillSwitch;
import org.example.smashhub.chat.ai.repository.AiFaqEntryRepository;
import org.example.smashhub.chat.ai.service.AiEmbeddingService;
import org.example.smashhub.chat.ai.service.AiFaqService;
import org.example.smashhub.chat.enums.HandlerMode;
import org.example.smashhub.chat.repository.ConversationRepository;
import org.example.smashhub.common.response.ApiResponse;
import org.example.smashhub.exception.AppException;
import org.example.smashhub.exception.ErrorCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping({"/api/v1/chat/admin/ai", "/api/v1/admin/ai"})
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AiAdminRestController {

    AiKillSwitch aiKillSwitch;
    AiBudgetTracker aiBudgetTracker;
    AiEmbeddingService aiEmbeddingService;
    AiFaqService aiFaqService;
    AiFaqEntryRepository aiFaqEntryRepository;
    ConversationRepository conversationRepository;
    CircuitBreakerRegistry circuitBreakerRegistry;
    AiProperties aiProperties;

    /**
     * Lấy tổng quan trạng thái Trợ lý AI (Kill switch, ngân sách, Circuit Breaker).
     */
    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> getAiStatus() {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(AiResilienceConfig.GEMINI_CIRCUIT_BREAKER);
        AiBudgetTracker.BudgetCheckResult budget = aiBudgetTracker.checkDailyBudget();

        Map<String, Object> status = new HashMap<>();
        status.put("enabled", aiKillSwitch.isAiEnabled());
        status.put("chatModel", aiProperties.getChatModel());
        status.put("embeddingModel", aiProperties.getEmbeddingModel());
        status.put("circuitBreakerState", cb.getState().name());
        status.put("circuitBreakerFailureRate", cb.getMetrics().getFailureRate());
        status.put("dailyCostUsd", budget.getCurrentCostUsd());
        status.put("dailyLimitUsd", budget.getLimitUsd());
        status.put("budgetUsedPercent", budget.getPercent());
        status.put("isBudgetExceeded", !budget.isAllowed());

        return ApiResponse.<Map<String, Object>>builder().result(status).build();
    }

    /**
     * Bật / tắt khẩn cấp Trợ lý AI (Kill Switch).
     */
    @PostMapping("/kill-switch")
    public ApiResponse<Boolean> setKillSwitch(@RequestParam boolean enabled) {
        aiKillSwitch.setAiEnabled(enabled);
        return ApiResponse.<Boolean>builder().result(aiKillSwitch.isAiEnabled()).build();
    }

    /**
     * Chuyển đổi thủ công chế độ xử lý của hội thoại (AI <-> HUMAN).
     */
    @PatchMapping("/conversations/{conversationId}/handler-mode")
    public ApiResponse<String> setConversationHandlerMode(
            @PathVariable Long conversationId,
            @RequestParam HandlerMode mode
    ) {
        conversationRepository.findById(conversationId)
                .orElseThrow(() -> new AppException(ErrorCode.CONVERSATION_NOT_FOUND));

        conversationRepository.updateHandlerMode(conversationId, mode);
        return ApiResponse.<String>builder().result("Updated handler mode to " + mode.name()).build();
    }

    /**
     * Kích hoạt nạp embedding catalog sản phẩm theo lô có kiểm soát rate limit.
     */
    @PostMapping("/ingest")
    public ApiResponse<AiEmbeddingService.IngestResult> triggerIngestion(
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "5") int maxPages,
            @RequestParam(defaultValue = "500") long delayMs
    ) {
        AiEmbeddingService.IngestResult result = aiEmbeddingService.ingestProductsBatch(pageSize, maxPages, delayMs);
        return ApiResponse.<AiEmbeddingService.IngestResult>builder().result(result).build();
    }

    /**
     * Danh sách FAQ cache.
     */
    @GetMapping("/faqs")
    public ApiResponse<Page<AiFaqEntry>> getFaqs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Page<AiFaqEntry> faqs = aiFaqEntryRepository.findAll(PageRequest.of(page, size));
        return ApiResponse.<Page<AiFaqEntry>>builder().result(faqs).build();
    }

    /**
     * Thêm FAQ mới (tự động chuẩn hóa và tính hash SHA-256).
     */
    @PostMapping("/faqs")
    public ApiResponse<AiFaqEntry> createFaq(@RequestBody Map<String, String> body) {
        String question = body.get("question");
        String answer = body.get("answer");
        String category = body.getOrDefault("category", "GENERAL");

        if (question == null || question.isBlank() || answer == null || answer.isBlank()) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }

        String normalized = aiFaqService.normalizeQuestion(question);
        String hash = aiFaqService.computeSha256(normalized);

        AiFaqEntry entry = AiFaqEntry.builder()
                .normalizedQuestion(normalized)
                .questionHash(hash)
                .answer(answer)
                .category(category)
                .active(true)
                .usageCount(0L)
                .build();

        AiFaqEntry saved = aiFaqEntryRepository.save(entry);
        return ApiResponse.<AiFaqEntry>builder().result(saved).build();
    }
}

