package org.example.smashhub.chat.ai.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

@Configuration
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AiResilienceConfig {

    AiProperties aiProperties;

    public static final String GEMINI_CIRCUIT_BREAKER = "geminiCircuitBreaker";
    public static final String GEMINI_RETRY = "geminiRetry";

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        AiProperties.CircuitBreakerConfig cbConfig = aiProperties.getCircuitBreaker();

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(cbConfig.getSlidingWindowSize())
                .failureRateThreshold(cbConfig.getFailureRateThreshold())
                .waitDurationInOpenState(Duration.ofSeconds(cbConfig.getWaitDurationInOpenStateSeconds()))
                .permittedNumberOfCallsInHalfOpenState(cbConfig.getPermittedNumberOfCallsInHalfOpenState())
                .recordExceptions(
                        HttpServerErrorException.class,
                        HttpClientErrorException.TooManyRequests.class,
                        ResourceAccessException.class,
                        TimeoutException.class,
                        IOException.class
                )
                .ignoreExceptions(
                        HttpClientErrorException.BadRequest.class,
                        HttpClientErrorException.Unauthorized.class,
                        HttpClientErrorException.Forbidden.class,
                        HttpClientErrorException.NotFound.class
                )
                .build();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        CircuitBreaker cb = registry.circuitBreaker(GEMINI_CIRCUIT_BREAKER);

        cb.getEventPublisher()
                .onStateTransition(event -> log.warn("[GEMINI CIRCUIT BREAKER] State transition: {}", event.getStateTransition()))
                .onError(event -> log.warn("[GEMINI CIRCUIT BREAKER] Recorded error: {}", event.getThrowable().getMessage()));

        return registry;
    }

    @Bean
    public RetryRegistry retryRegistry() {
        AiProperties.RetryConfig rConfig = aiProperties.getRetry();

        RetryConfig config = RetryConfig.custom()
                .maxAttempts(rConfig.getMaxAttempts() + 1) // 1 initial + maxAttempts retries
                .waitDuration(Duration.ofMillis(rConfig.getBackoffMillis()))
                .retryOnException(throwable -> {
                    // CHỈ retry khi 429 (TooManyRequests), 503 (ServiceUnavailable) hoặc Timeout
                    if (throwable instanceof HttpClientErrorException.TooManyRequests) {
                        return true;
                    }
                    if (throwable instanceof HttpServerErrorException.ServiceUnavailable) {
                        return true;
                    }
                    if (throwable instanceof HttpServerErrorException.GatewayTimeout) {
                        return true;
                    }
                    if (throwable instanceof ResourceAccessException || throwable instanceof TimeoutException) {
                        return true;
                    }
                    // KHÔNG retry khi 400, 401, 403, 404...
                    return false;
                })
                .build();

        RetryRegistry registry = RetryRegistry.of(config);
        Retry retry = registry.retry(GEMINI_RETRY);

        retry.getEventPublisher()
                .onRetry(event -> log.info("[GEMINI RETRY] Attempt #{}: {}", event.getNumberOfRetryAttempts(), event.getLastThrowable().getMessage()));

        return registry;
    }
}

