package org.example.smashhub.chat.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "smashhub.chat")
public class ChatProperties {

    private List<String> allowedOrigins = new ArrayList<>(List.of(
            "http://localhost:3000",
            "http://localhost:5173",
            "http://localhost:8080",
            "http://localhost:8443",
            "http://localhost:*"
    ));

    private Cluster cluster = new Cluster();
    private RateLimit rateLimit = new RateLimit();
    private Attachment attachment = new Attachment();

    @Getter
    @Setter
    public static class Cluster {
        // Đồng bộ broadcast giữa nhiều node backend bằng Redis Pub/Sub (không cần RabbitMQ)
        private boolean enableRedisPubSub = false;
        private String redisChannel = "smashhub:chat:cluster:broadcast";
    }

    @Getter
    @Setter
    public static class RateLimit {
        private int maxMessages = 15;
        private int windowSeconds = 10;
    }

    @Getter
    @Setter
    public static class Attachment {
        private int maxUploadsPerMinute = 5;
    }
}
