package org.example.smashhub.chat.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.example.smashhub.catalog.entity.Product;
import org.example.smashhub.catalog.repository.ProductRepository;
import org.example.smashhub.chat.ai.client.GeminiApiClient;
import org.example.smashhub.chat.ai.client.dto.GeminiEmbedResponse;
import org.example.smashhub.chat.ai.config.AiProperties;
import org.example.smashhub.chat.ai.entity.AiKnowledgeChunk;
import org.example.smashhub.chat.ai.limiter.GeminiRateLimiter;
import org.example.smashhub.chat.ai.repository.AiKnowledgeChunkRepository;
import org.example.smashhub.common.enums.ProductStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AiEmbeddingService {

    static final String EMBED_CACHE_PREFIX = "smashhub:ai:cache:embed:";
    static final String INGEST_PROGRESS_KEY = "smashhub:ai:ingest:checkpoint:product_page";

    GeminiApiClient geminiApiClient;
    GeminiRateLimiter geminiRateLimiter;
    AiProperties aiProperties;
    AiKnowledgeChunkRepository knowledgeChunkRepository;
    ProductRepository productRepository;
    StringRedisTemplate redisTemplate;
    ObjectMapper objectMapper;

    @Autowired(required = false)
    MeterRegistry meterRegistry;

    public String computeSha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not found", e);
        }
    }

    /**
     * Lấy vector nhúng cho một đoạn văn bản (có cache Redis theo content hash).
     */
    public List<Double> getEmbedding(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        String hash = computeSha256(text.trim());
        String cacheKey = EMBED_CACHE_PREFIX + hash;

        // 1. Kiểm tra cache Redis
        try {
            String cachedJson = redisTemplate.opsForValue().get(cacheKey);
            if (cachedJson != null && !cachedJson.isBlank()) {
                if (meterRegistry != null) {
                    meterRegistry.counter("smashhub.ai.cache.hit", "type", "EMBEDDING").increment();
                }
                return objectMapper.readValue(cachedJson, new TypeReference<List<Double>>() {});
            }
        } catch (Exception e) {
            log.warn("Failed to read embedding cache", e);
        }

        // 2. Kiểm tra limiter embedding
        String embedModel = aiProperties.getEmbeddingModel();
        long estimatedTokens = geminiRateLimiter.estimateTokens(text);
        GeminiRateLimiter.RateLimitResult limitCheck = geminiRateLimiter.tryAcquireEmbedding(embedModel, 1, estimatedTokens);
        if (!limitCheck.isAllowed()) {
            log.warn("Embedding rate limit reached ({}), skipping live API call", limitCheck.getReason());
            return null;
        }

        // 3. Gọi Gemini API
        try {
            GeminiEmbedResponse response = geminiApiClient.embedContent(embedModel, text);
            List<Double> vector = response != null ? response.getValues() : null;

            if (vector != null && !vector.isEmpty()) {
                try {
                    String json = objectMapper.writeValueAsString(vector);
                    long ttl = aiProperties.getCache().getEmbeddingTtlSeconds();
                    redisTemplate.opsForValue().set(cacheKey, json, Duration.ofSeconds(ttl));
                } catch (JsonProcessingException e) {
                    log.error("Failed to serialize embedding vector to JSON", e);
                }
            }
            return vector;
        } catch (Exception e) {
            log.error("Failed to extract embedding from Gemini", e);
            return null;
        }
    }

    /**
     * Nạp catalog sản phẩm theo lô nhỏ (batch), giãn nhịp (pace control),
     * so sánh hash để bỏ qua nội dung không đổi và có thể tiếp tục (resumable) khi bị gián đoạn.
     */
    @Transactional
    public IngestResult ingestProductsBatch(int pageSize, int maxPagesToProcess, long delayBetweenBatchesMs) {
        int startPage = 0;
        try {
            String lastPageStr = redisTemplate.opsForValue().get(INGEST_PROGRESS_KEY);
            if (lastPageStr != null) {
                startPage = Integer.parseInt(lastPageStr);
            }
        } catch (Exception e) {
            log.warn("Failed to read ingest checkpoint, starting from page 0", e);
        }

        int processedCount = 0;
        int skippedUnchanged = 0;
        int embeddedCount = 0;
        int currentPage = startPage;

        for (int i = 0; i < maxPagesToProcess; i++) {
            Page<Product> page = productRepository.findAll(PageRequest.of(currentPage, pageSize));
            if (page.isEmpty()) {
                // Đã duyệt hết toàn bộ sản phẩm -> Reset checkpoint về 0
                redisTemplate.delete(INGEST_PROGRESS_KEY);
                log.info("Finished full catalog ingestion sweep! Total processed: {}", processedCount);
                break;
            }

            for (Product p : page.getContent()) {
                if (p.getProductStatus() != ProductStatus.ACTIVE) {
                    continue;
                }
                processedCount++;
                String chunkText = String.format("Sản phẩm: %s. Giá: %s VNĐ. Danh mục: %s. Thương hiệu: %s. Mô tả: %s",
                        p.getName(),
                        p.getPrice() != null ? p.getPrice().toPlainString() : "Liên hệ",
                        p.getCategory() != null ? p.getCategory().getName() : "",
                        p.getBrand() != null ? p.getBrand().getName() : "",
                        p.getDescription() != null ? p.getDescription() : "");

                String hash = computeSha256(chunkText);

                // So sánh hash: nếu chunk đã tồn tại và hash không đổi thì BỎ QUA không gọi Gemini
                Optional<AiKnowledgeChunk> existing = knowledgeChunkRepository
                        .findByEntityTypeAndEntityIdAndContentHash("PRODUCT", p.getId(), hash);
                if (existing.isPresent()) {
                    skippedUnchanged++;
                    continue;
                }

                // Cần tính embedding mới
                List<Double> vector = getEmbedding(chunkText);
                String vectorJson = null;
                try {
                    if (vector != null) vectorJson = objectMapper.writeValueAsString(vector);
                } catch (Exception ignored) {}

                AiKnowledgeChunk chunk = AiKnowledgeChunk.builder()
                        .entityType("PRODUCT")
                        .entityId(p.getId())
                        .contentHash(hash)
                        .chunkText(chunkText)
                        .embeddingJson(vectorJson)
                        .status("ACTIVE")
                        .build();

                knowledgeChunkRepository.save(chunk);
                embeddedCount++;

                // Giãn nhịp tránh burst quota
                if (delayBetweenBatchesMs > 0) {
                    try {
                        Thread.sleep(delayBetweenBatchesMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            currentPage++;
            redisTemplate.opsForValue().set(INGEST_PROGRESS_KEY, String.valueOf(currentPage));

            if (currentPage >= page.getTotalPages()) {
                redisTemplate.delete(INGEST_PROGRESS_KEY);
                break;
            }
        }

        return new IngestResult(processedCount, skippedUnchanged, embeddedCount, currentPage);
    }

    public record IngestResult(int processed, int skipped, int newlyEmbedded, int nextCheckpointPage) {}
}

