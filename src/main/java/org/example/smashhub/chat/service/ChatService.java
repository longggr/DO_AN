package org.example.smashhub.chat.service;

import org.example.smashhub.chat.dto.request.ConversationFilterRequest;
import org.example.smashhub.chat.dto.request.InitConversationRequest;
import org.example.smashhub.chat.dto.request.ReadRequest;
import org.example.smashhub.chat.dto.request.SendMessageRequest;
import org.example.smashhub.chat.dto.request.TypingRequest;
import org.example.smashhub.chat.dto.response.ChatMessageResponse;
import org.example.smashhub.chat.dto.response.ConversationResponse;
import org.example.smashhub.chat.enums.ConversationStatus;
import org.example.smashhub.common.dto.PageResponse;
import org.example.smashhub.file.dto.FileUploadResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

public interface ChatService {

    ChatMessageResponse sendMessage(String currentUsername, SendMessageRequest request);

    void handleTyping(String currentUsername, TypingRequest request);

    void handleRead(String currentUsername, ReadRequest request);

    ConversationResponse initConversation(String currentCustomerUsername, InitConversationRequest request);

    ConversationResponse getMyConversation(String currentCustomerUsername);

    PageResponse<ConversationResponse> getConversationsForAdmin(ConversationFilterRequest request);

    ConversationResponse assignConversation(Long conversationId, String adminUsername, Long targetStaffId);

    ConversationResponse resolveConversation(Long conversationId, String adminUsername);

    ConversationResponse reopenConversation(Long conversationId, String adminUsername);

    PageResponse<ChatMessageResponse> getMessages(Long conversationId, String currentUsername, Pageable pageable);

    FileUploadResponse uploadAttachment(String currentUsername, MultipartFile file);

    ChatMessageResponse submitMessageFeedback(Long messageId, String currentUsername, org.example.smashhub.chat.enums.AiFeedback feedback);

    String updateHandlerMode(Long conversationId, org.example.smashhub.chat.enums.HandlerMode mode);
}

