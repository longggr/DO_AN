package org.example.smashhub.chat.ai.config;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AiAsyncConfig {

    AiProperties aiProperties;

    @Bean(name = "geminiTaskExecutor")
    public ThreadPoolTaskExecutor geminiTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        AiProperties.ExecutorConfig cfg = aiProperties.getExecutor();

        executor.setCorePoolSize(cfg.getCorePoolSize());
        executor.setMaxPoolSize(cfg.getMaxPoolSize());
        executor.setQueueCapacity(cfg.getQueueCapacity());
        executor.setKeepAliveSeconds(cfg.getKeepAliveSeconds());
        executor.setThreadNamePrefix("gemini-ai-exec-");

        // Khi hàng đợi đầy: ném RejectedExecutionException để tầng nghiệp vụ bắt được và fallback tức thì
        executor.setRejectedExecutionHandler(new RejectedExecutionHandler() {
            @Override
            public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
                log.warn("[AI EXECUTOR QUEUE FULL] Active: {}, Queue: {}/{}. Rejecting new task to trigger Fallback.",
                        executor.getActiveCount(), executor.getQueue().size(), cfg.getQueueCapacity());
                throw new RejectedExecutionException("Gemini task executor queue is full");
            }
        });

        executor.initialize();
        return executor;
    }
}

