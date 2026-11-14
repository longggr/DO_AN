package org.example.smashhub.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.smashhub.auth.service.JwtService;
import org.example.smashhub.chat.dto.event.ConversationUpdateEvent;
import org.example.smashhub.chat.dto.request.ReadRequest;
import org.example.smashhub.chat.dto.request.SendMessageRequest;
import org.example.smashhub.chat.dto.response.ChatMessageResponse;
import org.example.smashhub.chat.dto.response.ConversationResponse;
import org.example.smashhub.chat.entity.Conversation;
import org.example.smashhub.chat.enums.ConversationStatus;
import org.example.smashhub.chat.enums.MessageType;
import org.example.smashhub.chat.repository.ChatMessageRepository;
import org.example.smashhub.chat.repository.ConversationRepository;
import org.example.smashhub.chat.service.ChatRedisService;
import org.example.smashhub.chat.service.ChatService;
import org.example.smashhub.common.enums.AuthProvider;
import org.example.smashhub.common.enums.Status;
import org.example.smashhub.user.entity.Role;
import org.example.smashhub.user.entity.User;
import org.example.smashhub.user.repository.RoleRepository;
import org.example.smashhub.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class WebSocketChatIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0.33")
            .withDatabaseName("smashhub_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @LocalServerPort
    int port;

    @Autowired
    UserRepository userRepository;
    @Autowired
    RoleRepository roleRepository;
    @Autowired
    ConversationRepository conversationRepository;
    @Autowired
    ChatMessageRepository chatMessageRepository;
    @Autowired
    JwtService jwtService;
    @Autowired
    ChatService chatService;
    @Autowired
    ChatRedisService chatRedisService;

    User customerA;
    User customerB;
    User admin;

    String tokenCustomerA;
    String tokenCustomerB;
    String tokenAdmin;

    Conversation convA;

    @BeforeEach
    void setUp() {
        chatMessageRepository.deleteAll();
        conversationRepository.deleteAll();
        userRepository.deleteAll();

        Role roleCust = roleRepository.findById("CUSTOMER")
                .orElseGet(() -> {
                    Role r = new Role();
                    r.setName("CUSTOMER");
                    r.setDescription("Customer");
                    return roleRepository.save(r);
                });
        Role roleAdm = roleRepository.findById("ADMIN")
                .orElseGet(() -> {
                    Role r = new Role();
                    r.setName("ADMIN");
                    r.setDescription("Admin");
                    return roleRepository.save(r);
                });

        customerA = new User();
        customerA.setUsername("custA_" + UUID.randomUUID().toString().substring(0, 6));
        customerA.setEmail("custA@" + UUID.randomUUID() + ".com");
        customerA.setPassword("password123");
        customerA.setRole(roleCust);
        customerA.setStatus(Status.ACTIVE);
        customerA.setAuthProvider(AuthProvider.LOCAL);
        customerA = userRepository.save(customerA);

        customerB = new User();
        customerB.setUsername("custB_" + UUID.randomUUID().toString().substring(0, 6));
        customerB.setEmail("custB@" + UUID.randomUUID() + ".com");
        customerB.setPassword("password123");
        customerB.setRole(roleCust);
        customerB.setStatus(Status.ACTIVE);
        customerB.setAuthProvider(AuthProvider.LOCAL);
        customerB = userRepository.save(customerB);

        admin = new User();
        admin.setUsername("admin_" + UUID.randomUUID().toString().substring(0, 6));
        admin.setEmail("admin@" + UUID.randomUUID() + ".com");
        admin.setPassword("password123");
        admin.setRole(roleAdm);
        admin.setStatus(Status.ACTIVE);
        admin.setAuthProvider(AuthProvider.LOCAL);
        admin = userRepository.save(admin);

        tokenCustomerA = jwtService.generateToken(customerA);
        tokenCustomerB = jwtService.generateToken(customerB);
        tokenAdmin = jwtService.generateToken(admin);

        convA = conversationRepository.save(Conversation.builder()
                .customer(customerA)
                .status(ConversationStatus.OPEN)
                .build());
    }

    private WebSocketStompClient createStompClient() {
        List<Transport> transports = Collections.singletonList(new WebSocketTransport(new StandardWebSocketClient()));
        SockJsClient sockJsClient = new SockJsClient(transports);
        WebSocketStompClient client = new WebSocketStompClient(sockJsClient);
        client.setMessageConverter(new MappingJackson2MessageConverter());
        return client;
    }

    @Test
    @DisplayName("IT: Connect STOMP với Bearer Token hợp lệ thành công")
    void testConnectWithValidToken() throws Exception {
        WebSocketStompClient stompClient = createStompClient();
        String url = "ws://localhost:" + port + "/cms/ws-smashhub";

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + tokenCustomerA);

        StompSession session = stompClient.connectAsync(
                url,
                new WebSocketHttpHeaders(),
                connectHeaders,
                new StompSessionHandlerAdapter() {}
        ).get(5, TimeUnit.SECONDS);

        assertNotNull(session);
        assertTrue(session.isConnected());
        session.disconnect();
    }

    @Test
    @DisplayName("IT: Gửi tin nhắn real-time qua WebSocket và nhận được trên topic hội thoại")
    void testSendMessageAndReceiveOnConversationTopic() throws Exception {
        WebSocketStompClient stompClient = createStompClient();
        String url = "ws://localhost:" + port + "/cms/ws-smashhub";

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + tokenCustomerA);

        StompSession session = stompClient.connectAsync(
                url,
                new WebSocketHttpHeaders(),
                connectHeaders,
                new StompSessionHandlerAdapter() {}
        ).get(5, TimeUnit.SECONDS);

        BlockingQueue<ChatMessageResponse> receivedMessages = new ArrayBlockingQueue<>(1);

        session.subscribe("/topic/conversations/" + convA.getId(), new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return ChatMessageResponse.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                receivedMessages.offer((ChatMessageResponse) payload);
            }
        });

        // Chờ subscription sẵn sàng
        Thread.sleep(500);

        SendMessageRequest request = SendMessageRequest.builder()
                .conversationId(convA.getId())
                .clientMessageId(UUID.randomUUID().toString())
                .content("SmashHub tư vấn giúp em vợt Nanoflare 800 Pro")
                .messageType(MessageType.TEXT)
                .build();

        session.send("/app/chat.send", request);

        ChatMessageResponse response = receivedMessages.poll(5, TimeUnit.SECONDS);
        assertNotNull(response);
        assertEquals("SmashHub tư vấn giúp em vợt Nanoflare 800 Pro", response.getContent());
        assertEquals(customerA.getId(), response.getSenderId());

        session.disconnect();
    }

    @Test
    @DisplayName("IT: Admin nhận ConversationUpdateEvent trên /topic/admin/conversations khi có tin nhắn mới")
    void testAdminReceivesConversationUpdateEvent() throws Exception {
        WebSocketStompClient adminStompClient = createStompClient();
        String url = "ws://localhost:" + port + "/cms/ws-smashhub";

        StompHeaders adminHeaders = new StompHeaders();
        adminHeaders.add("Authorization", "Bearer " + tokenAdmin);

        StompSession adminSession = adminStompClient.connectAsync(
                url,
                new WebSocketHttpHeaders(),
                adminHeaders,
                new StompSessionHandlerAdapter() {}
        ).get(5, TimeUnit.SECONDS);

        BlockingQueue<ConversationUpdateEvent> adminEvents = new ArrayBlockingQueue<>(1);

        adminSession.subscribe("/topic/admin/conversations", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return ConversationUpdateEvent.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                adminEvents.offer((ConversationUpdateEvent) payload);
            }
        });

        Thread.sleep(500);

        // Khách A gửi tin nhắn qua REST hoặc STOMP
        SendMessageRequest request = SendMessageRequest.builder()
                .conversationId(convA.getId())
                .clientMessageId(UUID.randomUUID().toString())
                .content("Xin chào shop!")
                .messageType(MessageType.TEXT)
                .build();
        chatService.sendMessage(customerA.getUsername(), request);

        ConversationUpdateEvent event = adminEvents.poll(5, TimeUnit.SECONDS);
        assertNotNull(event);
        assertEquals(convA.getId(), event.conversationId());
        assertEquals(1, event.adminUnreadCount());

        adminSession.disconnect();
    }
}
