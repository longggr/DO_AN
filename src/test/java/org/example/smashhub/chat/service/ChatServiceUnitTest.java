package org.example.smashhub.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.smashhub.catalog.entity.Product;
import org.example.smashhub.catalog.repository.ProductImageRepository;
import org.example.smashhub.catalog.repository.ProductRepository;
import org.example.smashhub.chat.config.ChatProperties;
import org.example.smashhub.chat.dto.request.SendMessageRequest;
import org.example.smashhub.chat.dto.response.ChatMessageResponse;
import org.example.smashhub.chat.entity.ChatMessage;
import org.example.smashhub.chat.entity.Conversation;
import org.example.smashhub.chat.enums.ConversationStatus;
import org.example.smashhub.chat.enums.MessageType;
import org.example.smashhub.chat.enums.SenderRole;
import org.example.smashhub.chat.exception.ChatRateLimitExceededException;
import org.example.smashhub.chat.mapper.ChatMapper;
import org.example.smashhub.chat.repository.ChatMessageRepository;
import org.example.smashhub.chat.repository.ConversationRepository;
import org.example.smashhub.chat.service.impl.ChatRedisServiceImpl;
import org.example.smashhub.chat.service.impl.ChatServiceImpl;
import org.example.smashhub.common.enums.OrderStatus;
import org.example.smashhub.common.enums.ProductStatus;
import org.example.smashhub.exception.AppException;
import org.example.smashhub.exception.ErrorCode;
import org.example.smashhub.file.service.FileService;
import org.example.smashhub.order.entity.Order;
import org.example.smashhub.order.repository.OrderRepository;
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
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatServiceUnitTest {

    @Mock
    ConversationRepository conversationRepository;
    @Mock
    ChatMessageRepository chatMessageRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    ProductRepository productRepository;
    @Mock
    ProductImageRepository productImageRepository;
    @Mock
    OrderRepository orderRepository;
    @Mock
    ChatRedisService chatRedisService;
    @Mock
    ChatMapper chatMapper;
    @Mock
    ChatBroadcaster chatBroadcaster;
    @Mock
    FileService fileService;
    @Mock
    ObjectMapper objectMapper;
    @Mock
    org.example.smashhub.chat.ai.service.AiChatService aiChatService;
    @Mock
    org.example.smashhub.chat.ai.service.AiAssistantService aiAssistantService;

    @InjectMocks
    ChatServiceImpl chatService;

    User customerUser;
    User otherCustomerUser;
    User adminUser;
    Conversation conversation;
    Role customerRole;
    Role adminRole;

    @BeforeEach
    void setUp() {
        customerRole = new Role();
        customerRole.setName("CUSTOMER");

        adminRole = new Role();
        adminRole.setName("ADMIN");

        customerUser = new User();
        customerUser.setId(10L);
        customerUser.setUsername("customer1");
        customerUser.setRole(customerRole);

        otherCustomerUser = new User();
        otherCustomerUser.setId(20L);
        otherCustomerUser.setUsername("customer2");
        otherCustomerUser.setRole(customerRole);

        adminUser = new User();
        adminUser.setId(99L);
        adminUser.setUsername("admin1");
        adminUser.setRole(adminRole);

        conversation = Conversation.builder()
                .customer(customerUser)
                .status(ConversationStatus.OPEN)
                .adminUnreadCount(0)
                .customerUnreadCount(0)
                .build();
        conversation.setId(100L);
    }

    @Test
    @DisplayName("Rate limit: Ném ngoại lệ ChatRateLimitExceededException khi vượt ngưỡng 15 tin")
    void testRateLimitExceeded() {
        doThrow(new ChatRateLimitExceededException(5))
                .when(chatRedisService).checkRateLimit(customerUser.getId());
        when(userRepository.findByUsername("customer1")).thenReturn(Optional.of(customerUser));

        SendMessageRequest request = SendMessageRequest.builder()
                .conversationId(100L)
                .clientMessageId("client-uuid-1")
                .content("Hỏi thông số vợt Yonex Astrox 88D Pro")
                .messageType(MessageType.TEXT)
                .build();

        ChatRateLimitExceededException ex = assertThrows(
                ChatRateLimitExceededException.class,
                () -> chatService.sendMessage("customer1", request)
        );

        assertEquals(5, ex.getRemainingSeconds());
        verify(chatMessageRepository, never()).save(any());
    }

    @Test
    @DisplayName("Idempotency: Trả về tin nhắn cũ khi clientMessageId đã tồn tại, không lưu trùng DB")
    void testIdempotencyDuplicateClientMessageId() {
        when(userRepository.findByUsername("customer1")).thenReturn(Optional.of(customerUser));

        ChatMessage existingMessage = ChatMessage.builder()
                .conversation(conversation)
                .sender(customerUser)
                .senderRole(SenderRole.CUSTOMER)
                .content("Tin nhắn trùng lặp")
                .messageType(MessageType.TEXT)
                .clientMessageId("uuid-duplicate-123")
                .build();
        existingMessage.setId(555L);

        ChatMessageResponse mappedResponse = ChatMessageResponse.builder()
                .id(555L)
                .clientMessageId("uuid-duplicate-123")
                .content("Tin nhắn trùng lặp")
                .build();

        when(chatMessageRepository.findByConversationIdAndClientMessageId(100L, "uuid-duplicate-123"))
                .thenReturn(Optional.of(existingMessage));
        when(chatMapper.toChatMessageResponse(existingMessage)).thenReturn(mappedResponse);

        SendMessageRequest request = SendMessageRequest.builder()
                .conversationId(100L)
                .clientMessageId("uuid-duplicate-123")
                .content("Tin nhắn trùng lặp")
                .messageType(MessageType.TEXT)
                .build();

        ChatMessageResponse response = chatService.sendMessage("customer1", request);

        assertNotNull(response);
        assertEquals(555L, response.getId());
        assertEquals("uuid-duplicate-123", response.getClientMessageId());
        verify(chatMessageRepository, never()).save(any());
        verify(conversationRepository, never()).updateOnCustomerMessage(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("Kiểm quyền: Khách B không được phép gửi tin vào hội thoại của Khách A")
    void testPermissionDeniedForOtherCustomer() {
        when(userRepository.findByUsername("customer2")).thenReturn(Optional.of(otherCustomerUser));
        when(chatMessageRepository.findByConversationIdAndClientMessageId(100L, "uuid-unauth"))
                .thenReturn(Optional.empty());
        when(conversationRepository.findById(100L)).thenReturn(Optional.of(conversation));

        SendMessageRequest request = SendMessageRequest.builder()
                .conversationId(100L)
                .clientMessageId("uuid-unauth")
                .content("Xâm nhập hội thoại người khác")
                .messageType(MessageType.TEXT)
                .build();

        AppException ex = assertThrows(AppException.class, () -> chatService.sendMessage("customer2", request));
        assertEquals(ErrorCode.CHAT_ACCESS_DENIED, ex.getErrorCode());
        verify(chatMessageRepository, never()).save(any());
    }

    @Test
    @DisplayName("Validation ORDER_CARD: Khách không được gửi đơn hàng không thuộc về mình")
    void testOrderCardValidationNotOwnedByCustomer() {
        when(userRepository.findByUsername("customer1")).thenReturn(Optional.of(customerUser));
        when(chatMessageRepository.findByConversationIdAndClientMessageId(100L, "uuid-order-1"))
                .thenReturn(Optional.empty());
        when(conversationRepository.findById(100L)).thenReturn(Optional.of(conversation));

        // Đơn hàng thuộc sở hữu của customer2
        Order foreignOrder = Order.builder()
                .user(otherCustomerUser)
                .orderStatus(OrderStatus.PAID)
                .total(BigDecimal.valueOf(2500000))
                .build();
        foreignOrder.setId(999L);

        when(orderRepository.findById(999L)).thenReturn(Optional.of(foreignOrder));

        SendMessageRequest request = SendMessageRequest.builder()
                .conversationId(100L)
                .clientMessageId("uuid-order-1")
                .messageType(MessageType.ORDER_CARD)
                .referencedOrderId(999L)
                .build();

        AppException ex = assertThrows(AppException.class, () -> chatService.sendMessage("customer1", request));
        assertEquals(ErrorCode.CHAT_ORDER_NOT_OWNED, ex.getErrorCode());
        verify(chatMessageRepository, never()).save(any());
    }

    @Test
    @DisplayName("Validation ORDER_CARD: Admin chỉ được gửi đơn hàng thuộc về đúng khách hàng của hội thoại")
    void testOrderCardValidationAdminSendingMismatchedCustomerOrder() {
        when(userRepository.findByUsername("admin1")).thenReturn(Optional.of(adminUser));
        when(chatMessageRepository.findByConversationIdAndClientMessageId(100L, "uuid-admin-order"))
                .thenReturn(Optional.empty());
        when(conversationRepository.findById(100L)).thenReturn(Optional.of(conversation));

        // Đơn hàng thuộc sở hữu của customer2, nhưng conversation này là của customer1
        Order mismatchedOrder = Order.builder()
                .user(otherCustomerUser)
                .orderStatus(OrderStatus.SHIPPING)
                .total(BigDecimal.valueOf(1800000))
                .build();
        mismatchedOrder.setId(888L);

        when(orderRepository.findById(888L)).thenReturn(Optional.of(mismatchedOrder));

        SendMessageRequest request = SendMessageRequest.builder()
                .conversationId(100L)
                .clientMessageId("uuid-admin-order")
                .messageType(MessageType.ORDER_CARD)
                .referencedOrderId(888L)
                .build();

        AppException ex = assertThrows(AppException.class, () -> chatService.sendMessage("admin1", request));
        assertEquals(ErrorCode.CHAT_ORDER_NOT_OWNED, ex.getErrorCode());
        verify(chatMessageRepository, never()).save(any());
    }

    @Test
    @DisplayName("State Transition: Khách gửi tin nhắn vào hội thoại RESOLVED thì tự động kích hoạt lại OPEN")
    void testConversationAutoReopenWhenCustomerMessages() {
        conversation.setStatus(ConversationStatus.RESOLVED);

        when(userRepository.findByUsername("customer1")).thenReturn(Optional.of(customerUser));
        when(chatMessageRepository.findByConversationIdAndClientMessageId(100L, "uuid-reopen"))
                .thenReturn(Optional.empty());
        when(conversationRepository.findById(100L)).thenReturn(Optional.of(conversation));

        ChatMessage savedMsg = ChatMessage.builder()
                .conversation(conversation)
                .sender(customerUser)
                .senderRole(SenderRole.CUSTOMER)
                .content("Cần hỏi thêm về độ căng dây BG65Ti")
                .messageType(MessageType.TEXT)
                .clientMessageId("uuid-reopen")
                .build();
        savedMsg.setId(777L);

        ChatMessageResponse mappedResponse = ChatMessageResponse.builder()
                .id(777L)
                .clientMessageId("uuid-reopen")
                .content("Cần hỏi thêm về độ căng dây BG65Ti")
                .build();

        when(chatMessageRepository.save(any(ChatMessage.class))).thenReturn(savedMsg);
        when(chatMapper.toChatMessageResponse(savedMsg)).thenReturn(mappedResponse);

        SendMessageRequest request = SendMessageRequest.builder()
                .conversationId(100L)
                .clientMessageId("uuid-reopen")
                .content("Cần hỏi thêm về độ căng dây BG65Ti")
                .messageType(MessageType.TEXT)
                .build();

        ChatMessageResponse response = chatService.sendMessage("customer1", request);

        assertNotNull(response);
        // Kiểm tra updateOnCustomerMessage được gọi để JPQL tự chuyển status = OPEN nếu đang RESOLVED
        verify(conversationRepository).updateOnCustomerMessage(eq(100L), anyString(), any(LocalDateTime.class));
    }
}
