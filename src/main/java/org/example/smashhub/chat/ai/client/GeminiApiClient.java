package org.example.smashhub.chat.ai.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.example.smashhub.chat.ai.client.dto.GeminiEmbedRequest;
import org.example.smashhub.chat.ai.client.dto.GeminiEmbedResponse;
import org.example.smashhub.chat.ai.client.dto.GeminiGenerateRequest;
import org.example.smashhub.chat.ai.client.dto.GeminiGenerateResponse;
import org.example.smashhub.chat.ai.config.AiProperties;
import org.example.smashhub.chat.ai.config.AiResilienceConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class GeminiApiClient {

    AiProperties aiProperties;
    RestClient restClient;
    CircuitBreaker circuitBreaker;
    MeterRegistry meterRegistry;

    public GeminiApiClient(
            AiProperties aiProperties,
            CircuitBreakerRegistry circuitBreakerRegistry,
            @Autowired(required = false) MeterRegistry meterRegistry
    ) {
        this.aiProperties = aiProperties;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(AiResilienceConfig.GEMINI_CIRCUIT_BREAKER);
        this.meterRegistry = meterRegistry;
        this.restClient = RestClient.builder()
                .baseUrl(aiProperties.getBaseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Gọi API sinh nội dung Gemini có bao bọc bởi Resilience4j Circuit Breaker.
     */
    public GeminiGenerateResponse generateContent(String modelName, GeminiGenerateRequest request) {
        String apiKey = aiProperties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY is not configured");
        }

        Supplier<GeminiGenerateResponse> supplier = () -> {
            long start = System.nanoTime();
            try {
                GeminiGenerateResponse response = restClient.post()
                        .uri("/v1beta/models/{model}:generateContent", modelName)
                        .header("x-goog-api-key", apiKey)
                        .body(request)
                        .retrieve()
                        .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                            int statusCode = resp.getStatusCode().value();
                            String retryAfter = resp.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
                            log.warn("[GEMINI API 4xx] Status: {}, Retry-After: {}", statusCode, retryAfter);
                            if (statusCode == 429) {
                                if (meterRegistry != null) {
                                    meterRegistry.counter("smashhub.ai.http.429", "model", modelName).increment();
                                }
                                throw HttpClientErrorException.create(resp.getStatusCode(), resp.getStatusText(),
                                        resp.getHeaders(), resp.getBody().readAllBytes(), null);
                            }
                            throw HttpClientErrorException.create(resp.getStatusCode(), resp.getStatusText(),
                                    resp.getHeaders(), resp.getBody().readAllBytes(), null);
                        })
                        .onStatus(HttpStatusCode::is5xxServerError, (req, resp) -> {
                            log.error("[GEMINI API 5xx] Server error from Gemini: {}", resp.getStatusCode());
                            throw HttpServerErrorException.create(resp.getStatusCode(), resp.getStatusText(),
                                    resp.getHeaders(), resp.getBody().readAllBytes(), null);
                        })
                        .body(GeminiGenerateResponse.class);

                if (meterRegistry != null) {
                    Timer.builder("smashhub.ai.latency")
                            .tag("model", modelName)
                            .tag("status", "SUCCESS")
                            .register(meterRegistry)
                            .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
                }
                return response;
            } catch (Exception e) {
                if (meterRegistry != null) {
                    Timer.builder("smashhub.ai.latency")
                            .tag("model", modelName)
                            .tag("status", "ERROR")
                            .register(meterRegistry)
                            .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
                }
                throw e;
            }
        };

        // Thực thi qua Circuit Breaker
        return circuitBreaker.executeSupplier(supplier);
    }

    /**
     * Gọi API trích xuất vector nhúng (embedding) cho văn bản.
     */
    public GeminiEmbedResponse embedContent(String modelName, String text) {
        String apiKey = aiProperties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY is not configured");
        }

        String fullModelPath = "models/" + modelName;
        GeminiEmbedRequest request = GeminiEmbedRequest.of(fullModelPath, text);

        Supplier<GeminiEmbedResponse> supplier = () -> restClient.post()
                .uri("/v1beta/models/{model}:embedContent", modelName)
                .header("x-goog-api-key", apiKey)
                .body(request)
                .retrieve()
                .body(GeminiEmbedResponse.class);

        return circuitBreaker.executeSupplier(supplier);
    }
}
