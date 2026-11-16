package org.example.smashhub.chat.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.example.smashhub.catalog.entity.Product;
import org.example.smashhub.catalog.repository.ProductImageRepository;
import org.example.smashhub.catalog.repository.ProductRepository;
import org.example.smashhub.chat.dto.event.ConversationUpdateEvent;
import org.example.smashhub.chat.dto.event.ReadEvent;
import org.example.smashhub.chat.dto.event.TypingEvent;
import org.example.smashhub.chat.dto.request.ConversationFilterRequest;
import org.example.smashhub.chat.dto.request.InitConversationRequest;
import org.example.smashhub.chat.dto.request.ReadRequest;
import org.example.smashhub.chat.dto.request.SendMessageRequest;
import org.example.smashhub.chat.dto.request.TypingRequest;
import org.example.smashhub.chat.dto.response.ChatMessageResponse;
import org.example.smashhub.chat.dto.response.ConversationResponse;
import org.example.smashhub.chat.dto.response.OrderSummaryCardDto;
import org.example.smashhub.chat.dto.response.ProductSummaryCardDto;
import org.example.smashhub.chat.entity.ChatMessage;
import org.example.smashhub.chat.entity.Conversation;
import org.example.smashhub.chat.enums.ConversationStatus;
import org.example.smashhub.chat.enums.MessageType;
import org.example.smashhub.chat.enums.SenderRole;
import org.example.smashhub.chat.mapper.ChatMapper;
import org.example.smashhub.chat.enums.AiFeedback;
import org.example.smashhub.chat.enums.HandlerMode;
import org.example.smashhub.chat.repository.ChatMessageRepository;
import org.example.smashhub.chat.repository.ConversationRepository;
import org.example.smashhub.chat.security.OrderOwnership;
import org.example.smashhub.chat.service.ChatBroadcaster;
import org.example.smashhub.chat.service.ChatRedisService;
import org.example.smashhub.chat.service.ChatService;
import org.example.smashhub.common.dto.PageResponse;
import org.example.smashhub.common.enums.ProductStatus;
import org.example.smashhub.exception.AppException;
import org.example.smashhub.exception.ErrorCode;
import org.example.smashhub.file.dto.FileUploadResponse;
import org.example.smashhub.file.enums.FileFolder;
import org.example.smashhub.file.service.FileService;
import org.example.smashhub.order.entity.Order;
import org.example.smashhub.order.repository.OrderRepository;
import org.example.smashhub.user.entity.User;
import org.example.smashhub.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class ChatServiceImpl implements ChatService {

    static final Pattern CLOUDINARY_CHAT_PATTERN =
            Pattern.compile("^https?://res\\.cloudinary\\.com/[^/]+/image/upload/.*/smashhub/chat/.*$");

    final ConversationRepository conversationRepository;
    final ChatMessageRepository chatMessageRepository;
    final UserRepository userRepository;
    final ProductRepository productRepository;
    final ProductImageRepository productImageRepository;
    final OrderRepository orderRepository;
    final ChatRedisService chatRedisService;
    final ChatMapper chatMapper;
    final ChatBroadcaster chatBroadcaster;
    final FileService fileService;
    final ObjectMapper objectMapper;
    final org.example.smashhub.chat.ai.service.AiChatService aiChatService;
    final org.example.smashhub.chat.ai.service.AiAssistantService aiAssistantService;

    @Autowired(required = false)
    MeterRegistry meterRegistry;

    // =========================================================================
    // 1. GỬI TIN NHẮN REAL-TIME
    // =========================================================================

    @Override
    @Transactional
    public ChatMessageResponse sendMessage(String currentUsername, SendMessageRequest request) {
        User sender = findUserByUsername(currentUsername);

        // 1. Rate limit (Lua atomic trên Redis)
        chatRedisService.checkRateLimit(sender.getId());

        // 2. Idempotency: Kiểm tra chống gửi trùng lặp qua clientMessageId
        Optional<ChatMessage> existing = chatMessageRepository
                .findByConversationIdAndClientMessageId(request.getConversationId(), request.getClientMessageId());
        if (existing.isPresent()) {
            log.info("Idempotent hit: clientMessageId={} already exists in conversationId={}",
                    request.getClientMessageId(), request.getConversationId());
            return buildSingleResponseWithCards(existing.get());
        }

        // 3. Tải và kiểm tra quyền trên hội thoại
        Conversation conversation = conversationRepository.findById(request.getConversationId())
                .orElseThrow(() -> new AppException(ErrorCode.CONVERSATION_NOT_FOUND));

        boolean isAdminOrStaff = isUserAdminOrStaff(sender);
        boolean isOwner = conversation.getCustomer().getId().equals(sender.getId());

        if (!isAdminOrStaff && !isOwner) {
            log.warn("Forbidden chat attempt: user {} does not belong to conversation {}", sender.getUsername(), conversation.getId());
            throw new AppException(ErrorCode.CHAT_ACCESS_DENIED);
        }

        SenderRole senderRole = isAdminOrStaff ? SenderRole.ADMIN : SenderRole.CUSTOMER;

        // 4. Validate payload theo từng messageType
        String content = request.getContent();
        String snapshotJson = null;
        ProductSummaryCardDto productCard = null;
        OrderSummaryCardDto orderCard = null;

        switch (request.getMessageType()) {
            case TEXT -> {
                if (content == null || content.isBlank()) {
                    throw new AppException(ErrorCode.CHAT_MESSAGE_INVALID);
                }
                content = sanitizeText(content.trim());

                // Auto-recovery thông minh:
                // Nếu khách hàng gửi tin nhắn khi hội thoại đang ở HUMAN:
                // Tự động khôi phục về AI nếu:
                // 1. Khách quay lại sau hơn 15 phút (hết phiên cũ)
                // 2. Không có admin online
                // 3. Khách chủ động hỏi tư vấn sản phẩm hoặc chào hỏi lại
                // 4. Lý do chuyển trước đó là do mô hình/hệ thống tạm chuyển (hoặc null)
                // VÀ tin nhắn không phải là khiếu nại hay yêu cầu gặp người thật.
                if (senderRole == SenderRole.CUSTOMER && conversation.getHandlerMode() == org.example.smashhub.chat.enums.HandlerMode.HUMAN) {
                    boolean isHandoffRequest = aiAssistantService.isHumanHandoffIntent(content)
                            || aiAssistantService.isSensitiveTopicOrOrderMutation(content);

                    if (!isHandoffRequest) {
                        LocalDateTime lastMsgTime = conversation.getLastMessageAt();
                        boolean isSessionExpired = lastMsgTime == null || lastMsgTime.isBefore(LocalDateTime.now().minusMinutes(15));
                        boolean noAdminOnline = !chatRedisService.isAnyAdminOnline();
                        String lower = content != null ? content.toLowerCase().trim() : "";
                        boolean isConsultOrGreeting = lower.contains("xin chào") || lower.contains("chào")
                                || lower.contains("tư vấn") || lower.contains("cần tư vấn") || lower.contains("giá")
                                || lower.contains("cho mình hỏi") || lower.contains("shop ơi") || lower.contains("ad ơi")
                                || lower.contains("mua") || lower.contains("vợt") || lower.contains("giày")
                                || request.getReferencedProductId() != null
                                || request.getMessageType() == MessageType.PRODUCT_CARD;

                        String hReason = conversation.getHandoffReason();
                        boolean isSystemOrNullHandoff = hReason == null || "MODEL_REQUESTED".equals(hReason)
                                || "OUT_OF_DOMAIN".equals(hReason) || "CONSECUTIVE_UNCERTAIN".equals(hReason);

                        if (isSessionExpired || noAdminOnline || isConsultOrGreeting || isSystemOrNullHandoff) {
                            log.info("Auto-recovering conversationId={} from HUMAN back to AI mode. sessionExpired={}, noAdminOnline={}, isGreeting={}",
                                    conversation.getId(), isSessionExpired, noAdminOnline, isConsultOrGreeting);
                            conversationRepository.resetToAiMode(conversation.getId());
                            conversation.setHandlerMode(org.example.smashhub.chat.enums.HandlerMode.AI);
                            conversation.setStatus(ConversationStatus.OPEN);
                            conversation.setAssignedAdmin(null);
                            conversation.setHandoffReason(null);
                        }
                    }
                }
            }
            case IMAGE -> {
                if (request.getAttachmentUrl() == null || request.getAttachmentUrl().isBlank()) {
                    throw new AppException(ErrorCode.CHAT_MESSAGE_INVALID);
                }
                if (!CLOUDINARY_CHAT_PATTERN.matcher(request.getAttachmentUrl()).matches()) {
                    log.warn("Rejected image URL outside Cloudinary smashhub/chat: {}", request.getAttachmentUrl());
                    throw new AppException(ErrorCode.CHAT_IMAGE_URL_INVALID);
                }
            }
            case PRODUCT_CARD -> {
                if (request.getReferencedProductId() == null) {
                    throw new AppException(ErrorCode.PRODUCT_NOT_FOUND);
                }
                Product product = productRepository.findById(request.getReferencedProductId())
                        .orElseThrow(() -> new AppException(ErrorCode.PRODUCT_NOT_FOUND));
                if (product.getProductStatus() != ProductStatus.ACTIVE) {
                    throw new AppException(ErrorCode.CHAT_PRODUCT_NOT_AVAILABLE);
                }
                String thumb = resolveProductThumbnail(product.getId());
                productCard = ProductSummaryCardDto.builder()
                        .id(product.getId())
                        .name(product.getName())
                        .slug(product.getSlug())
                        .price(product.getPrice())
                        .salePrice(product.getSalePrice())
                        .thumbnailUrl(thumb)
                        .build();
                try {
                    snapshotJson = objectMapper.writeValueAsString(productCard);
                } catch (JsonProcessingException e) {
                    log.error("Failed to serialize product card snapshot", e);
                }
            }
            case ORDER_CARD -> {
                if (request.getReferencedOrderId() == null) {
                    throw new AppException(ErrorCode.ORDER_NOT_FOUND);
                }
                Order order = orderRepository.findById(request.getReferencedOrderId())
                        .orElseThrow(() -> new AppException(ErrorCode.ORDER_NOT_FOUND));
                // Nếu khách gửi: đơn phải của chính khách; Nếu admin gửi: đơn phải thuộc khách của hội thoại này
                Long targetCustomerId = conversation.getCustomer().getId();
                if (senderRole == SenderRole.CUSTOMER && !OrderOwnership.belongsTo(order, sender.getId())) {
                    throw new AppException(ErrorCode.CHAT_ORDER_NOT_OWNED);
                }
                if (senderRole == SenderRole.ADMIN && !OrderOwnership.belongsTo(order, targetCustomerId)) {
                    throw new AppException(ErrorCode.CHAT_ORDER_NOT_OWNED);
                }
                orderCard = OrderSummaryCardDto.builder()
                        .id(order.getId())
                        .orderStatus(order.getOrderStatus())
                        .total(order.getTotal())
                        .createdAt(order.getCreateDate())
                        .itemsCount(order.getItems() != null ? order.getItems().size() : 0)
                        .build();
            }
        }

        // 5. Lưu tin nhắn vào DB
        ChatMessage message = ChatMessage.builder()
                .conversation(conversation)
                .sender(sender)
                .senderRole(senderRole)
                .content(content)
                .messageType(request.getMessageType())
                .attachmentUrl(request.getAttachmentUrl())
                .referencedProductId(request.getReferencedProductId())
                .referencedOrderId(request.getReferencedOrderId())
                .snapshotJson(snapshotJson)
                .clientMessageId(request.getClientMessageId())
                .isRead(false)
                .build();

        ChatMessage savedMessage = chatMessageRepository.save(message);

        // 6. Cập nhật Conversation bằng JPQL atomic
        String preview = generatePreview(savedMessage, productCard, orderCard);
        LocalDateTime now = LocalDateTime.now();

        if (senderRole == SenderRole.CUSTOMER) {
            conversationRepository.updateOnCustomerMessage(conversation.getId(), preview, now);
        } else {
            conversationRepository.updateOnAdminMessage(conversation.getId(), preview, now, sender.getId());
        }

        // Dựng response DTO
        ChatMessageResponse response = chatMapper.toChatMessageResponse(savedMessage);
        response.setProductCard(productCard);
        response.setOrderCard(orderCard);

        // 7. Broadcast sau khi transaction commit thành công
        final Long convId = conversation.getId();
        final SenderRole role = senderRole;
        final boolean adminOnline = chatRedisService.isAnyAdminOnline();

        registerAfterCommitAction(() -> {
            // Broadcast tin nhắn tới kênh hội thoại (hỗ trợ đa instance qua Redis Pub/Sub)
            chatBroadcaster.broadcast("/topic/conversations/" + convId, response);

            // Broadcast sự kiện cập nhật hội thoại cho admin dashboard
            conversationRepository.findWithDetailsById(convId).ifPresent(updatedConv -> {
                ConversationUpdateEvent updateEvent = chatMapper.toConversationUpdateEvent(updatedConv);
                chatBroadcaster.broadcast("/topic/admin/conversations", updateEvent);
            });

            // Ghi nhận metrics
            if (meterRegistry != null) {
                meterRegistry.counter("smashhub.chat.messages.sent", "role", role.name()).increment();
            }
            log.info("Chat message broadcasted: convId={}, senderId={}, role={}, type={}, clientMsgId={}",
                    convId, sender.getId(), role, savedMessage.getMessageType(), savedMessage.getClientMessageId());

            // 8. Chuyển cho Trợ lý AI (nếu handlerMode == AI) hoặc Auto-reply (nếu HUMAN và không có admin online)
            if (role == SenderRole.CUSTOMER && savedMessage.getMessageType() == MessageType.TEXT) {
                org.example.smashhub.chat.enums.HandlerMode mode = conversation.getHandlerMode();
                if (mode == org.example.smashhub.chat.enums.HandlerMode.AI) {
                    aiAssistantService.handleCustomerMessageAsync(convId, sender, savedMessage.getContent());
                } else if (!adminOnline) {
                    if (chatRedisService.tryAcquireAutoReplyLock(convId)) {
                        triggerSystemAutoReply(convId, sender);
                    }
                }
            }
        });

        return response;
    }

    private void triggerSystemAutoReply(Long convId, User customer) {
        try {
            sendSystemAutoReplyAsync(convId, customer);
        } catch (Exception e) {
            log.error("Failed to send system auto reply for conversationId={}", convId, e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendSystemAutoReplyAsync(Long convId, User customer) {
        Conversation conversation = conversationRepository.findById(convId).orElse(null);
        if (conversation == null) return;

        User systemUser = userRepository.findByUsername("system")
                .or(() -> userRepository.findById(1L))
                .orElse(customer);

        String autoReplyText = "Cảm ơn bạn đã liên hệ SmashHub! Hiện tại đang ngoài giờ hỗ trợ trực tuyến của tư vấn viên. Chúng tôi sẽ phản hồi bạn ngay khi có nhân viên online.";

        ChatMessage systemMsg = ChatMessage.builder()
                .conversation(conversation)
                .sender(systemUser)
                .senderRole(SenderRole.SYSTEM)
                .content(autoReplyText)
                .messageType(MessageType.TEXT)
                .clientMessageId(UUID.randomUUID().toString())
                .isRead(false)
                .build();

        ChatMessage saved = chatMessageRepository.save(systemMsg);
        LocalDateTime now = LocalDateTime.now();
        conversationRepository.updateOnSystemMessage(convId, autoReplyText, now);

        ChatMessageResponse response = chatMapper.toChatMessageResponse(saved);
        chatBroadcaster.broadcast("/topic/conversations/" + convId, response);
        log.info("System auto-reply sent to conversationId={}", convId);
    }

    // =========================================================================
    // 2. TYPING & READ EVENT
    // =========================================================================

    @Override
    public void handleTyping(String currentUsername, TypingRequest request) {
        User user = findUserByUsername(currentUsername);
        boolean allowed = chatRedisService.tryAcquireTypingThrottle(user.getId(), request.getConversationId());
        if (!allowed) {
            return; // Throttle: bỏ qua event vượt quá 1 event/giây
        }

        boolean isAdminOrStaff = isUserAdminOrStaff(user);
        SenderRole senderRole = isAdminOrStaff ? SenderRole.ADMIN : SenderRole.CUSTOMER;

        TypingEvent event = new TypingEvent(
                request.getConversationId(),
                user.getId(),
                user.getUsername(),
                senderRole,
                request.isTyping()
        );
        chatBroadcaster.broadcast("/topic/conversations/" + request.getConversationId() + "/typing", event);
    }

    @Override
    @Transactional
    public void handleRead(String currentUsername, ReadRequest request) {
        User user = findUserByUsername(currentUsername);
        Long convId = request.getConversationId();

        Conversation conversation = conversationRepository.findById(convId)
                .orElseThrow(() -> new AppException(ErrorCode.CONVERSATION_NOT_FOUND));

        boolean isAdminOrStaff = isUserAdminOrStaff(user);
        boolean isOwner = conversation.getCustomer().getId().equals(user.getId());

        if (!isAdminOrStaff && !isOwner) {
            throw new AppException(ErrorCode.CHAT_ACCESS_DENIED);
        }

        LocalDateTime now = LocalDateTime.now();
        SenderRole readerRole = isAdminOrStaff ? SenderRole.ADMIN : SenderRole.CUSTOMER;

        if (readerRole == SenderRole.CUSTOMER) {
            // Khách đọc các tin của ADMIN
            chatMessageRepository.markMessagesAsReadByRole(convId, SenderRole.ADMIN);
            conversationRepository.resetCustomerUnreadCount(convId);
        } else {
            // Admin đọc các tin của CUSTOMER
            chatMessageRepository.markMessagesAsReadByRole(convId, SenderRole.CUSTOMER);
            conversationRepository.resetAdminUnreadCount(convId);
        }

        registerAfterCommitAction(() -> {
            ReadEvent readEvent = new ReadEvent(convId, readerRole, now);
            chatBroadcaster.broadcast("/topic/conversations/" + convId + "/read", readEvent);
        });
    }

    // =========================================================================
    // 3. REST API CHO KHÁCH HÀNG
    // =========================================================================

    @Override
    @Transactional
    public ConversationResponse initConversation(String currentCustomerUsername, InitConversationRequest request) {
        User customer = findUserByUsername(currentCustomerUsername);

        // Get-or-create với xử lý race condition an toàn
        Conversation conversation = conversationRepository.findByCustomerId(customer.getId())
                .orElseGet(() -> {
                    Conversation newConv = Conversation.builder()
                            .customer(customer)
                            .status(ConversationStatus.OPEN)
                            .customerUnreadCount(0)
                            .adminUnreadCount(0)
                            .build();
                    try {
                        return conversationRepository.saveAndFlush(newConv);
                    } catch (DataIntegrityViolationException ex) {
                        return conversationRepository.findByCustomerId(customer.getId())
                                .orElseThrow(() -> new AppException(ErrorCode.CONVERSATION_NOT_FOUND));
                    }
                });

        // Xử lý đính kèm sản phẩm khởi tạo nếu có
        if (request != null && request.getInitialProductId() != null) {
            boolean recentCardExists = chatMessageRepository
                    .existsByConversationIdAndMessageTypeAndReferencedProductIdAndCreateDateAfter(
                            conversation.getId(),
                            MessageType.PRODUCT_CARD,
                            request.getInitialProductId(),
                            LocalDateTime.now().minusHours(2)
                    );
            if (!recentCardExists) {
                SendMessageRequest sendReq = SendMessageRequest.builder()
                        .conversationId(conversation.getId())
                        .clientMessageId(UUID.randomUUID().toString())
                        .messageType(MessageType.PRODUCT_CARD)
                        .referencedProductId(request.getInitialProductId())
                        .build();
                sendMessage(currentCustomerUsername, sendReq);
            }
        }

        // Xử lý đính kèm đơn hàng khởi tạo nếu có
        if (request != null && request.getInitialOrderId() != null) {
            boolean orderCardExists = chatMessageRepository
                    .existsByConversationIdAndMessageTypeAndReferencedOrderId(
                            conversation.getId(),
                            MessageType.ORDER_CARD,
                            request.getInitialOrderId()
                    );
            if (!orderCardExists) {
                SendMessageRequest sendReq = SendMessageRequest.builder()
                        .conversationId(conversation.getId())
                        .clientMessageId(UUID.randomUUID().toString())
                        .messageType(MessageType.ORDER_CARD)
                        .referencedOrderId(request.getInitialOrderId())
                        .build();
                sendMessage(currentCustomerUsername, sendReq);
            }
        }

        Conversation fresh = conversationRepository.findWithDetailsById(conversation.getId()).orElse(conversation);
        ConversationResponse resp = chatMapper.toConversationResponse(fresh);
        resp.setAdminOnline(chatRedisService.isAnyAdminOnline());
        return resp;
    }

    @Override
    @Transactional
    public ConversationResponse getMyConversation(String currentCustomerUsername) {
        User customer = findUserByUsername(currentCustomerUsername);
        Conversation conversation = conversationRepository.findByCustomerId(customer.getId())
                .orElseGet(() -> {
                    Conversation newConv = Conversation.builder()
                            .customer(customer)
                            .status(ConversationStatus.OPEN)
                            .customerUnreadCount(0)
                            .adminUnreadCount(0)
                            .build();
                    try {
                        return conversationRepository.saveAndFlush(newConv);
                    } catch (DataIntegrityViolationException ex) {
                        return conversationRepository.findByCustomerId(customer.getId())
                                .orElseThrow(() -> new AppException(ErrorCode.CONVERSATION_NOT_FOUND));
                    }
                });

        ConversationResponse resp = chatMapper.toConversationResponse(conversation);
        resp.setAdminOnline(chatRedisService.isAnyAdminOnline());
        return resp;
    }

    // =========================================================================
    // 4. REST API CHO ADMIN / STAFF
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ConversationResponse> getConversationsForAdmin(ConversationFilterRequest request) {
        Pageable pageable = PageRequest.of(
                request.getPage(),
                request.getSize(),
                Sort.by(Sort.Direction.DESC, "lastMessageAt")
        );

        Page<Conversation> page = conversationRepository.findForAdmin(
                request.getStatus(),
                request.isUnreadOnly(),
                pageable
        );

        List<ConversationResponse> content = page.getContent().stream()
                .map(conv -> {
                    ConversationResponse r = chatMapper.toConversationResponse(conv);
                    r.setAdminOnline(chatRedisService.isUserOnline(conv.getCustomer().getId()));
                    return r;
                })
                .collect(Collectors.toList());

        return PageResponse.<ConversationResponse>builder()
                .pageNo(page.getNumber())
                .pageSize(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .isFirst(page.isFirst())
                .isLast(page.isLast())
                .content(content)
                .build();
    }

    @Override
    @Transactional
    public ConversationResponse assignConversation(Long conversationId, String adminUsername, Long targetStaffId) {
        User operator = findUserByUsername(adminUsername);
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new AppException(ErrorCode.CONVERSATION_NOT_FOUND));

        User targetStaff = operator;
        if (targetStaffId != null) {
            targetStaff = userRepository.findById(targetStaffId)
                    .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));
        }

        conversation.setAssignedAdmin(targetStaff);
        conversation.setStatus(ConversationStatus.HANDLING);
        Conversation updated = conversationRepository.save(conversation);

        ConversationResponse resp = chatMapper.toConversationResponse(updated);
        registerAfterCommitAction(() -> {
            ConversationUpdateEvent updateEvent = chatMapper.toConversationUpdateEvent(updated);
            chatBroadcaster.broadcast("/topic/admin/conversations", updateEvent);
        });

        return resp;
    }

    @Override
    @Transactional
    public ConversationResponse resolveConversation(Long conversationId, String adminUsername) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new AppException(ErrorCode.CONVERSATION_NOT_FOUND));

        conversation.setStatus(ConversationStatus.RESOLVED);
        Conversation updated = conversationRepository.save(conversation);

        ConversationResponse resp = chatMapper.toConversationResponse(updated);
        registerAfterCommitAction(() -> {
            ConversationUpdateEvent updateEvent = chatMapper.toConversationUpdateEvent(updated);
            chatBroadcaster.broadcast("/topic/admin/conversations", updateEvent);
        });

        return resp;
    }

    @Override
    @Transactional
    public ConversationResponse reopenConversation(Long conversationId, String adminUsername) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new AppException(ErrorCode.CONVERSATION_NOT_FOUND));

        conversation.setStatus(ConversationStatus.OPEN);
        Conversation updated = conversationRepository.save(conversation);

        ConversationResponse resp = chatMapper.toConversationResponse(updated);
        registerAfterCommitAction(() -> {
            ConversationUpdateEvent updateEvent = chatMapper.toConversationUpdateEvent(updated);
            chatBroadcaster.broadcast("/topic/admin/conversations", updateEvent);
        });

        return resp;
    }

    // =========================================================================
    // 5. TRA CỨU TIN NHẮN (BATCH-FETCH CHỐNG N+1)
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ChatMessageResponse> getMessages(Long conversationId, String currentUsername, Pageable pageable) {
        User user = findUserByUsername(currentUsername);
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new AppException(ErrorCode.CONVERSATION_NOT_FOUND));

        boolean isAdminOrStaff = isUserAdminOrStaff(user);
        boolean isOwner = conversation.getCustomer().getId().equals(user.getId());

        if (!isAdminOrStaff && !isOwner) {
            throw new AppException(ErrorCode.CHAT_ACCESS_DENIED);
        }

        Page<ChatMessage> page = isAdminOrStaff
                ? chatMessageRepository.findByConversationId(conversationId, pageable)
                : chatMessageRepository.findByConversationIdAndVisibleToCustomerTrue(conversationId, pageable);
        List<ChatMessage> messages = page.getContent();

        // 1. Thu thập tất cả productIds và orderIds để batch-fetch
        Set<Long> productIds = messages.stream()
                .map(ChatMessage::getReferencedProductId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Set<Long> orderIds = messages.stream()
                .map(ChatMessage::getReferencedOrderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // 2. Batch-fetch Product Cards (nếu chưa có snapshotJson)
        Map<Long, ProductSummaryCardDto> productCardMap = new HashMap<>();
        if (!productIds.isEmpty()) {
            Map<Long, Product> productMap = productRepository.findAllById(productIds).stream()
                    .collect(Collectors.toMap(Product::getId, p -> p));

            List<Object[]> thumbCandidates = productImageRepository.findThumbnailCandidates(productIds);
            Map<Long, String> thumbMap = new HashMap<>();
            for (Object[] row : thumbCandidates) {
                Long pId = (Long) row[0];
                String url = (String) row[1];
                thumbMap.putIfAbsent(pId, url);
            }

            productMap.forEach((id, prod) -> {
                productCardMap.put(id, ProductSummaryCardDto.builder()
                        .id(prod.getId())
                        .name(prod.getName())
                        .slug(prod.getSlug())
                        .price(prod.getPrice())
                        .salePrice(prod.getSalePrice())
                        .thumbnailUrl(thumbMap.get(id))
                        .build());
            });
        }

        // 3. Batch-fetch Order Cards (Luôn tra cứu Live để cập nhật trạng thái mới nhất)
        Map<Long, OrderSummaryCardDto> orderCardMap = new HashMap<>();
        if (!orderIds.isEmpty()) {
            List<Order> orders = orderRepository.findAllById(orderIds);
            for (Order o : orders) {
                orderCardMap.put(o.getId(), OrderSummaryCardDto.builder()
                        .id(o.getId())
                        .orderStatus(o.getOrderStatus())
                        .total(o.getTotal())
                        .createdAt(o.getCreateDate())
                        .itemsCount(o.getItems() != null ? o.getItems().size() : 0)
                        .build());
            }
        }

        // 4. Map sang response DTO
        List<ChatMessageResponse> content = messages.stream().map(msg -> {
            ChatMessageResponse resp = chatMapper.toChatMessageResponse(msg);

            // Gán Product Card: ưu tiên snapshot JSON lúc gửi, fallback batch query
            if (msg.getMessageType() == MessageType.PRODUCT_CARD) {
                if (msg.getSnapshotJson() != null && !msg.getSnapshotJson().isBlank()) {
                    try {
                        resp.setProductCard(objectMapper.readValue(msg.getSnapshotJson(), ProductSummaryCardDto.class));
                    } catch (JsonProcessingException e) {
                        resp.setProductCard(productCardMap.get(msg.getReferencedProductId()));
                    }
                } else {
                    resp.setProductCard(productCardMap.get(msg.getReferencedProductId()));
                }
            }

            // Gán Order Card: tra cứu Live
            if (msg.getMessageType() == MessageType.ORDER_CARD) {
                resp.setOrderCard(orderCardMap.get(msg.getReferencedOrderId()));
            }

            return resp;
        }).collect(Collectors.toList());

        return PageResponse.<ChatMessageResponse>builder()
                .pageNo(page.getNumber())
                .pageSize(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .isFirst(page.isFirst())
                .isLast(page.isLast())
                .content(content)
                .build();
    }

    // =========================================================================
    // 6. UPLOAD TỆP ĐÍNH KÈM
    // =========================================================================

    @Override
    public FileUploadResponse uploadAttachment(String currentUsername, MultipartFile file) {
        User user = findUserByUsername(currentUsername);
        chatRedisService.checkAttachmentUploadRateLimit(user.getId());

        FileUploadResponse uploadResponse = fileService.upload(file, FileFolder.CHAT);
        log.info("Chat attachment uploaded: userId={}, url={}", user.getId(), uploadResponse.getUrl());
        return uploadResponse;
    }

    // =========================================================================
    // HELPER METHODS
    // =========================================================================

    private User findUserByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));
    }

    private boolean isUserAdminOrStaff(User user) {
        if (user == null || user.getRole() == null) return false;
        String role = user.getRole().getName();
        return "ADMIN".equalsIgnoreCase(role) || "ROLE_ADMIN".equalsIgnoreCase(role)
                || "STAFF".equalsIgnoreCase(role) || "ROLE_STAFF".equalsIgnoreCase(role);
    }

    private String sanitizeText(String input) {
        if (input == null) return "";
        // Loại bỏ thẻ script độc hại chống XSS
        String sanitized = input.replaceAll("(?i)<script.*?>.*?</script>", "");
        // Escape HTML cơ bản
        return sanitized.replace("<", "&lt;").replace(">", "&gt;");
    }

    private String resolveProductThumbnail(Long productId) {
        List<Object[]> thumbs = productImageRepository.findThumbnailCandidates(Collections.singletonList(productId));
        if (thumbs != null && !thumbs.isEmpty()) {
            return (String) thumbs.get(0)[1];
        }
        return null;
    }

    private String generatePreview(ChatMessage message, ProductSummaryCardDto productCard, OrderSummaryCardDto orderCard) {
        return switch (message.getMessageType()) {
            case TEXT -> {
                String c = message.getContent() != null ? message.getContent() : "";
                yield c.length() > 250 ? c.substring(0, 247) + "..." : c;
            }
            case IMAGE -> "[Hình ảnh]";
            case PRODUCT_CARD -> {
                String name = productCard != null ? productCard.getName() : "Sản phẩm";
                yield "[Sản phẩm: " + (name.length() > 40 ? name.substring(0, 37) + "..." : name) + "]";
            }
            case ORDER_CARD -> "[Đơn hàng #" + message.getReferencedOrderId() + "]";
        };
    }

    private ChatMessageResponse buildSingleResponseWithCards(ChatMessage msg) {
        ChatMessageResponse resp = chatMapper.toChatMessageResponse(msg);
        if (msg.getMessageType() == MessageType.PRODUCT_CARD) {
            if (msg.getSnapshotJson() != null && !msg.getSnapshotJson().isBlank()) {
                try {
                    resp.setProductCard(objectMapper.readValue(msg.getSnapshotJson(), ProductSummaryCardDto.class));
                } catch (Exception ignored) {}
            }
        } else if (msg.getMessageType() == MessageType.ORDER_CARD && msg.getReferencedOrderId() != null) {
            orderRepository.findById(msg.getReferencedOrderId()).ifPresent(o -> {
                resp.setOrderCard(OrderSummaryCardDto.builder()
                        .id(o.getId())
                        .orderStatus(o.getOrderStatus())
                        .total(o.getTotal())
                        .createdAt(o.getCreateDate())
                        .itemsCount(o.getItems() != null ? o.getItems().size() : 0)
                        .build());
            });
        }
        return resp;
    }

    @Override
    @Transactional
    public ChatMessageResponse submitMessageFeedback(Long messageId, String currentUsername, AiFeedback feedback) {
        ChatMessage message = chatMessageRepository.findById(messageId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));

        User user = userRepository.findByUsername(currentUsername)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));

        Conversation conv = message.getConversation();
        boolean isOwner = conv.getCustomer().getId().equals(user.getId());
        boolean isAdmin = isUserAdminOrStaff(user);

        if (!isOwner && !isAdmin) {
            throw new AppException(ErrorCode.CHAT_ACCESS_DENIED);
        }

        message.setAiFeedback(feedback);
        ChatMessage saved = chatMessageRepository.save(message);

        if (feedback == AiFeedback.NOT_HELPFUL && conv.getHandlerMode() == HandlerMode.AI) {
            aiAssistantService.triggerHumanHandoff(
                    conv.getId(),
                    conv.getCustomer(),
                    "Dạ em đã ghi nhận phản hồi. Em xin phép chuyển cuộc trò chuyện sang chuyên viên tư vấn để hỗ trợ bạn trực tiếp ạ!",
                    "NOT_HELPFUL");
        }

        return chatMapper.toChatMessageResponse(saved);
    }

    @Override
    @Transactional
    public String updateHandlerMode(Long conversationId, org.example.smashhub.chat.enums.HandlerMode mode) {
        Conversation conv = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new AppException(ErrorCode.CONVERSATION_NOT_FOUND));
        conversationRepository.updateHandlerMode(conversationId, mode);
        conversationRepository.findWithDetailsById(conversationId).ifPresent(updatedConv -> {
            ConversationUpdateEvent event = chatMapper.toConversationUpdateEvent(updatedConv);
            chatBroadcaster.broadcast("/topic/admin/conversations", event);
        });
        return "Updated handler mode to " + mode.name();
    }

    @Override
    @Transactional
    public ConversationResponse switchToAiMode(String currentCustomerUsername) {
        User customer = findUserByUsername(currentCustomerUsername);
        Conversation conversation = conversationRepository.findByCustomerId(customer.getId())
                .orElseThrow(() -> new AppException(ErrorCode.CONVERSATION_NOT_FOUND));

        conversationRepository.resetToAiMode(conversation.getId());
        conversation.setHandlerMode(org.example.smashhub.chat.enums.HandlerMode.AI);
        conversation.setStatus(ConversationStatus.OPEN);
        conversation.setAssignedAdmin(null);
        conversation.setHandoffReason(null);

        Conversation fresh = conversationRepository.findWithDetailsById(conversation.getId()).orElse(conversation);
        ConversationUpdateEvent updateEvent = chatMapper.toConversationUpdateEvent(fresh);
        chatBroadcaster.broadcast("/topic/admin/conversations", updateEvent);
        chatBroadcaster.broadcast("/topic/conversations/" + conversation.getId() + "/mode", updateEvent);

        ConversationResponse resp = chatMapper.toConversationResponse(fresh);
        resp.setAdminOnline(chatRedisService.isAnyAdminOnline());
        log.info("Customer {} explicitly switched conversationId={} to AI mode", currentCustomerUsername, conversation.getId());
        return resp;
    }

    private void registerAfterCommitAction(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
