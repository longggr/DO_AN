package org.example.smashhub.chat.config;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.example.smashhub.auth.service.JwtService;
import org.example.smashhub.chat.repository.ConversationRepository;
import org.example.smashhub.chat.service.ChatRedisService;
import org.example.smashhub.user.entity.Role;
import org.example.smashhub.user.entity.User;
import org.example.smashhub.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.security.Principal;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebSocketSecurityInterceptorTest {

    @Mock
    JwtService jwtService;
    @Mock
    UserRepository userRepository;
    @Mock
    ConversationRepository conversationRepository;
    @Mock
    ChatRedisService chatRedisService;
    @Mock
    MessageChannel channel;

    @InjectMocks
    WebSocketSecurityInterceptor interceptor;

    User customerUser;
    User adminUser;

    @BeforeEach
    void setUp() {
        Role customerRole = new Role();
        customerRole.setName("CUSTOMER");
        customerUser = new User();
        customerUser.setId(10L);
        customerUser.setUsername("customer1");
        customerUser.setRole(customerRole);

        Role adminRole = new Role();
        adminRole.setName("ADMIN");
        adminUser = new User();
        adminUser.setId(99L);
        adminUser.setUsername("admin1");
        adminUser.setRole(adminRole);
    }

    @Test
    @DisplayName("STOMP CONNECT: Token JWT hợp lệ gán thành công Principal Authentication và ghi nhận Redis")
    void testConnectWithValidJwtToken() throws Exception {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setLeaveMutable(true);
        accessor.addNativeHeader("Authorization", "Bearer valid-jwt-token-123");
        accessor.setSessionId("sess-1");
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        com.nimbusds.jose.JWSHeader header = new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.HS256);
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .subject("customer1")
                .claim("scope", "ROLE_CUSTOMER")
                .build();
        SignedJWT realSignedJwt = new SignedJWT(header, claimsSet);
        realSignedJwt.sign(new com.nimbusds.jose.crypto.MACSigner("12345678901234567890123456789012".getBytes()));

        when(jwtService.verifyToken("valid-jwt-token-123")).thenReturn(realSignedJwt);
        when(userRepository.findByUsername("customer1")).thenReturn(Optional.of(customerUser));

        Message<?> result = interceptor.preSend(message, channel);

        assertNotNull(result);
        StompHeaderAccessor resAccessor = StompHeaderAccessor.wrap(result);
        assertNotNull(resAccessor.getUser());
        assertEquals("customer1", resAccessor.getUser().getName());
        verify(chatRedisService).recordUserConnected(eq("sess-1"), eq(10L), eq("customer1"), eq("CUSTOMER"));
    }

    @Test
    @DisplayName("STOMP CONNECT: Thiếu header Authorization ném ngoại lệ từ chối kết nối")
    void testConnectMissingAuthHeader() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThrows(
                AuthenticationCredentialsNotFoundException.class,
                () -> interceptor.preSend(message, channel)
        );
    }

    @Test
    @DisplayName("STOMP SUBSCRIBE: Khách hàng không có quyền subscribe vào kênh admin /topic/admin/conversations")
    void testCustomerCannotSubscribeToAdminTopic() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/admin/conversations");
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "customer1", null, Collections.singletonList(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
        );
        accessor.setUser(auth);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(message, channel));
    }

    @Test
    @DisplayName("STOMP SUBSCRIBE: Admin được phép subscribe vào kênh admin /topic/admin/conversations")
    void testAdminCanSubscribeToAdminTopic() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/admin/conversations");
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "admin1", null, Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
        accessor.setUser(auth);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertDoesNotThrow(() -> interceptor.preSend(message, channel));
    }

    @Test
    @DisplayName("STOMP SUBSCRIBE: Khách A bị chặn khi subscribe vào hội thoại của Khách B (/topic/conversations/200)")
    void testCustomerCannotSubscribeToOtherCustomerConversation() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/conversations/200");
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "customer1", null, Collections.singletonList(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
        );
        accessor.setUser(auth);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        when(userRepository.findByUsername("customer1")).thenReturn(Optional.of(customerUser));
        when(chatRedisService.getCachedConversationPermission(10L, 200L)).thenReturn(null);
        // Hội thoại 200 thuộc về khách hàng có ID 999
        when(conversationRepository.findCustomerIdByConversationId(200L)).thenReturn(Optional.of(999L));

        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(message, channel));
        verify(chatRedisService).cacheConversationPermission(10L, 200L, false);
    }

    @Test
    @DisplayName("STOMP SUBSCRIBE: Khách A được phép subscribe vào đúng hội thoại của mình (/topic/conversations/100)")
    void testCustomerCanSubscribeToOwnConversation() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/conversations/100");
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "customer1", null, Collections.singletonList(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
        );
        accessor.setUser(auth);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        when(userRepository.findByUsername("customer1")).thenReturn(Optional.of(customerUser));
        when(chatRedisService.getCachedConversationPermission(10L, 100L)).thenReturn(null);
        when(conversationRepository.findCustomerIdByConversationId(100L)).thenReturn(Optional.of(10L));

        assertDoesNotThrow(() -> interceptor.preSend(message, channel));
        verify(chatRedisService).cacheConversationPermission(10L, 100L, true);
    }
}
