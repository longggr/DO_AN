package org.example.smashhub.chat.ai.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.example.smashhub.chat.ai.config.AiProperties;
import org.example.smashhub.chat.ai.entity.AiFaqEntry;
import org.example.smashhub.chat.ai.repository.AiFaqEntryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.text.Normalizer;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AiFaqService {

    static final String FAQ_CACHE_PREFIX = "smashhub:ai:cache:faq:";
    static final Pattern DIACRITICS_PATTERN = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
    static final Pattern PUNCTUATION_PATTERN = Pattern.compile("[^a-z0-9\\s]");

    AiFaqEntryRepository aiFaqEntryRepository;
    StringRedisTemplate redisTemplate;
    AiProperties aiProperties;

    @Autowired(required = false)
    MeterRegistry meterRegistry;

    /**
     * Chuẩn hóa câu hỏi: chuyển chữ thường, loại bỏ dấu câu, chuẩn hóa khoảng trắng.
     */
    public String normalizeQuestion(String input) {
        if (input == null) return "";
        String normalized = input.trim().toLowerCase();
        // Giữ lại tiếng Việt hoặc chuyển sang không dấu để tăng tỉ lệ so khớp
        String decomposed = Normalizer.normalize(normalized, Normalizer.Form.NFD);
        String withoutDiacritics = DIACRITICS_PATTERN.matcher(decomposed).replaceAll("");
        String cleaned = PUNCTUATION_PATTERN.matcher(withoutDiacritics).replaceAll(" ");
        return cleaned.replaceAll("\\s+", " ").trim();
    }

    /**
     * Tính mã băm SHA-256 cho câu hỏi đã chuẩn hóa.
     */
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
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }

    /**
     * Tra cứu câu trả lời FAQ từ Redis Cache hoặc MySQL Database.
     */
    @Transactional
    public Optional<String> findFaqAnswer(String rawQuestion) {
        if (rawQuestion == null || rawQuestion.isBlank()) {
            return Optional.empty();
        }

        String normalized = normalizeQuestion(rawQuestion);
        if (normalized.isBlank()) {
            return Optional.empty();
        }

        String hash = computeSha256(normalized);
        String cacheKey = FAQ_CACHE_PREFIX + hash;

        // 1. Kiểm tra L1/L2 Redis Cache
        try {
            String cachedAnswer = redisTemplate.opsForValue().get(cacheKey);
            if (cachedAnswer != null && !cachedAnswer.isBlank()) {
                if (meterRegistry != null) {
                    meterRegistry.counter("smashhub.ai.cache.hit", "type", "FAQ").increment();
                }
                return Optional.of(cachedAnswer);
            }
        } catch (Exception e) {
            log.warn("Redis error reading FAQ cache for hash={}", hash, e);
        }

        // 2. Tra cứu DB MySQL
        Optional<AiFaqEntry> entryOpt = aiFaqEntryRepository.findByQuestionHashAndActiveTrue(hash);
        if (entryOpt.isPresent()) {
            AiFaqEntry entry = entryOpt.get();
            aiFaqEntryRepository.incrementUsageCount(entry.getId());

            // Lưu vào Redis cache với TTL cấu hình
            try {
                long ttl = aiProperties.getCache().getFaqTtlSeconds();
                redisTemplate.opsForValue().set(cacheKey, entry.getAnswer(), Duration.ofSeconds(ttl));
            } catch (Exception e) {
                log.warn("Redis error saving FAQ cache for hash={}", hash, e);
            }

            if (meterRegistry != null) {
                meterRegistry.counter("smashhub.ai.cache.hit", "type", "FAQ_DB").increment();
            }
            return Optional.of(entry.getAnswer());
        }

        return Optional.empty();
    }
}

