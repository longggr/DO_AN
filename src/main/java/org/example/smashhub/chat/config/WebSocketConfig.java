package org.example.smashhub.chat.config;

import jakarta.servlet.http.HttpServletRequest;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    ChatProperties chatProperties;
    WebSocketSecurityInterceptor webSocketSecurityInterceptor;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Cho phép toàn bộ localhost, LAN (192.168.x.x), ngrok và wildcard pattern * để mobile/dev không bị nghẽn CORS
        String[] originPatterns = new String[] {
                "*",
                "http://localhost:*",
                "http://127.0.0.1:*",
                "https://localhost:*",
                "http://192.168.*:*",
                "https://192.168.*:*",
                "http://10.*:*",
                "http://172.*:*",
                "http://localhost:8443",
                "http://localhost:8444",
                "https://*.ngrok-free.dev",
                "https://*.ngrok-free.app",
                "https://*.ngrok.io"
        };

        HandshakeInterceptor queryParamTokenInterceptor = new HandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                           WebSocketHandler wsHandler, Map<String, Object> attributes) {
                if (request instanceof ServletServerHttpRequest servletRequest) {
                    HttpServletRequest httpServletRequest = servletRequest.getServletRequest();
                    String token = httpServletRequest.getParameter("token");
                    if (token == null || token.isBlank()) {
                        token = httpServletRequest.getParameter("access_token");
                    }
                    if (token != null && !token.isBlank()) {
                        attributes.put("auth_token", token.trim());
                    }
                }
                return true;
            }

            @Override
            public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Exception exception) {
            }
        };

        // 1. Endpoint có SockJS fallback
        registry.addEndpoint("/ws-smashhub")
                .setAllowedOriginPatterns(originPatterns)
                .addInterceptors(queryParamTokenInterceptor)
                .withSockJS();

        // 2. Endpoint chuẩn thuần WebSocket
        registry.addEndpoint("/ws-smashhub")
                .setAllowedOriginPatterns(originPatterns)
                .addInterceptors(queryParamTokenInterceptor);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");

        // Sử dụng Simple Broker tiêu chuẩn kết hợp Heartbeat Scheduler
        // Để scale nhiều instance, sử dụng Redis Pub/Sub đồng bộ broadcast giữa các node (không cần RabbitMQ)
        ThreadPoolTaskScheduler heartbeatScheduler = new ThreadPoolTaskScheduler();
        heartbeatScheduler.setPoolSize(1);
        heartbeatScheduler.setThreadNamePrefix("ws-heartbeat-");
        heartbeatScheduler.initialize();

        registry.enableSimpleBroker("/topic", "/queue")
                .setTaskScheduler(heartbeatScheduler)
                .setHeartbeatValue(new long[]{10000, 10000});
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(64 * 1024)       // 64 KB
                    .setSendBufferSizeLimit(512 * 1024)   // 512 KB
                    .setSendTimeLimit(20 * 1000);         // 20s
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(webSocketSecurityInterceptor);
    }
}
