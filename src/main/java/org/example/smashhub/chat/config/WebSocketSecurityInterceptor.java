package org.example.smashhub.chat.config;

import com.nimbusds.jwt.SignedJWT;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.example.smashhub.auth.service.JwtService;
import org.example.smashhub.chat.repository.ConversationRepository;
import org.example.smashhub.chat.service.ChatRedisService;
import org.example.smashhub.user.entity.User;
import org.example.smashhub.user.repository.UserRepository;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class WebSocketSecurityInterceptor implements ChannelInterceptor {

    static Pattern CONVERSATION_TOPIC_PATTERN = Pattern.compile("^/topic/conversations/(\\d+)(/(typing|read))?$");
    static String ADMIN_TOPIC = "/topic/admin/conversations";

    JwtService jwtService;
    UserRepository userRepository;
    ConversationRepository conversationRepository;
    ChatRedisService chatRedisService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        StompCommand command = accessor.getCommand();

        if (StompCommand.CONNECT.equals(command)) {
            handleConnect(accessor);
        } else if (StompCommand.SUBSCRIBE.equals(command)) {
            handleSubscribe(accessor);
        }

        return message;
    }

    private void handleConnect(StompHeaderAccessor accessor) {
        String token = extractToken(accessor);
        if (token == null || token.isBlank()) {
            log.warn("STOMP CONNECT rejected: Missing Bearer token in header Authorization, token/access_token header, or URL query param");
            throw new AuthenticationCredentialsNotFoundException("Missing or malformed Bearer token");
        }

        try {
            SignedJWT signedJWT = jwtService.verifyToken(token);
            String username = signedJWT.getJWTClaimsSet().getSubject();
            String scope = (String) signedJWT.getJWTClaimsSet().getClaim("scope");

            List<GrantedAuthority> authorities = Collections.emptyList();
            if (scope != null && !scope.isBlank()) {
                authorities = Arrays.stream(scope.split(" "))
                        .map(SimpleGrantedAuthority::new)
                        .collect(Collectors.toList());
            }

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(username, null, authorities);
            accessor.setUser(authentication);

            User user = userRepository.findByUsername(username).orElse(null);
            if (user != null) {
                String roleName = user.getRole() != null ? user.getRole().getName() : "CUSTOMER";
                chatRedisService.recordUserConnected(accessor.getSessionId(), user.getId(), username, roleName);
            }
        } catch (Exception e) {
            log.warn("STOMP CONNECT rejected: Token verification failed - {}", e.getMessage());
            throw new AuthenticationCredentialsNotFoundException("Invalid or expired JWT token: " + e.getMessage());
        }
    }

    private String extractToken(StompHeaderAccessor accessor) {
        // 1. Native header Authorization (Bearer <token> hoặc <token>)
        String authHeader = accessor.getFirstNativeHeader("Authorization");
        if (authHeader != null && !authHeader.isBlank()) {
            authHeader = authHeader.trim();
            if (authHeader.startsWith("Bearer ") || authHeader.startsWith("bearer ")) {
                return authHeader.substring(7).trim();
            }
            return authHeader;
        }

        // 2. Native header token hoặc access_token
        String tokenHeader = accessor.getFirstNativeHeader("token");
        if (tokenHeader != null && !tokenHeader.isBlank()) {
            return tokenHeader.trim();
        }
        String accessTokenHeader = accessor.getFirstNativeHeader("access_token");
        if (accessTokenHeader != null && !accessTokenHeader.isBlank()) {
            return accessTokenHeader.trim();
        }

        // 3. Native header passcode (chuẩn STOMP login/passcode)
        String passcode = accessor.getPasscode();
        if (passcode != null && !passcode.isBlank()) {
            return passcode.trim();
        }

        // 4. Query param (?token=... hoặc ?access_token=...) được lưu vào session attributes
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes != null && sessionAttributes.containsKey("auth_token")) {
            Object tokenObj = sessionAttributes.get("auth_token");
            if (tokenObj != null) {
                return tokenObj.toString().trim();
            }
        }

        return null;
    }

    private void handleSubscribe(StompHeaderAccessor accessor) {
        Principal principal = accessor.getUser();
        if (principal == null) {
            log.warn("STOMP SUBSCRIBE rejected: Unauthenticated user");
            throw new AccessDeniedException("Unauthenticated user cannot subscribe");
        }

        String destination = accessor.getDestination();
        if (destination == null) {
            throw new AccessDeniedException("Destination cannot be null");
        }

        // 1. Kênh riêng cá nhân /user/queue/**
        if (destination.startsWith("/user/")) {
            return;
        }

        // 2. Kênh quản trị viên /topic/admin/conversations
        if (ADMIN_TOPIC.equals(destination)) {
            if (!hasStaffOrAdminRole(principal)) {
                log.warn("Forbidden SUBSCRIBE to admin topic by user={}", principal.getName());
                throw new AccessDeniedException("Only ADMIN or STAFF can subscribe to admin conversations topic");
            }
            return;
        }

        // 3. Kênh hội thoại cụ thể /topic/conversations/{id} kèm các kênh con /typing, /read
        Matcher matcher = CONVERSATION_TOPIC_PATTERN.matcher(destination);
        if (matcher.matches()) {
            Long conversationId = Long.parseLong(matcher.group(1));
            authorizeConversationAccess(principal, conversationId);
            return;
        }

        // 4. Mọi destination khác không nằm trong whitelist đều bị từ chối
        log.warn("Forbidden SUBSCRIBE to unknown destination '{}' by user={}", destination, principal.getName());
        throw new AccessDeniedException("Destination not permitted: " + destination);
    }

    private void authorizeConversationAccess(Principal principal, Long conversationId) {
        if (hasStaffOrAdminRole(principal)) {
            return; // Admin và Staff được phép subscribe mọi hội thoại
        }

        User customer = userRepository.findByUsername(principal.getName()).orElse(null);
        if (customer == null) {
            throw new AccessDeniedException("User not found");
        }

        // Kiểm tra cache quyền 60s trên Redis
        Boolean cachedPerm = chatRedisService.getCachedConversationPermission(customer.getId(), conversationId);
        if (Boolean.TRUE.equals(cachedPerm)) {
            return;
        }
        if (Boolean.FALSE.equals(cachedPerm)) {
            throw new AccessDeniedException("You are not authorized to subscribe to conversation " + conversationId);
        }

        // Tra cứu DB nếu chưa có trong cache
        Long ownerId = conversationRepository.findCustomerIdByConversationId(conversationId).orElse(null);
        boolean allowed = ownerId != null && ownerId.equals(customer.getId());

        chatRedisService.cacheConversationPermission(customer.getId(), conversationId, allowed);

        if (!allowed) {
            log.warn("User {} attempted to subscribe to conversation {} owned by {}", principal.getName(), conversationId, ownerId);
            throw new AccessDeniedException("You are not authorized to subscribe to conversation " + conversationId);
        }
    }

    private boolean hasStaffOrAdminRole(Principal principal) {
        if (principal instanceof UsernamePasswordAuthenticationToken auth) {
            return auth.getAuthorities().stream()
                    .anyMatch(a -> {
                        String name = a.getAuthority();
                        return "ROLE_ADMIN".equalsIgnoreCase(name)
                                || "ADMIN".equalsIgnoreCase(name)
                                || "ROLE_STAFF".equalsIgnoreCase(name)
                                || "STAFF".equalsIgnoreCase(name);
                    });
        }
        return false;
    }
}

