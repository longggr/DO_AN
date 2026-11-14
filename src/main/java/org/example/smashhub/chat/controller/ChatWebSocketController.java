package org.example.smashhub.chat.controller;

import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.example.smashhub.chat.dto.request.ReadRequest;
import org.example.smashhub.chat.dto.request.SendMessageRequest;
import org.example.smashhub.chat.dto.request.TypingRequest;
import org.example.smashhub.chat.dto.response.ChatErrorResponse;
import org.example.smashhub.chat.exception.ChatRateLimitExceededException;
import org.example.smashhub.chat.service.ChatService;
import org.example.smashhub.exception.AppException;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;

import java.security.Principal;

@Controller
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ChatWebSocketController {

    ChatService chatService;
    SimpMessagingTemplate messagingTemplate;

    @MessageMapping("/chat.send")
    public void sendMessage(@Valid @Payload SendMessageRequest request, Principal principal) {
        if (principal == null) {
            throw new AccessDeniedException("Unauthenticated");
        }
        chatService.sendMessage(principal.getName(), request);
    }

    @MessageMapping("/chat.typing")
    public void handleTyping(@Valid @Payload TypingRequest request, Principal principal) {
        if (principal == null) {
            return;
        }
        chatService.handleTyping(principal.getName(), request);
    }

    @MessageMapping("/chat.read")
    public void handleRead(@Valid @Payload ReadRequest request, Principal principal) {
        if (principal == null) {
            return;
        }
        chatService.handleRead(principal.getName(), request);
    }

    // =========================================================================
    // WEBSOCKET EXCEPTION HANDLER (Đẩy lỗi về /user/queue/errors)
    // =========================================================================

    @MessageExceptionHandler(ChatRateLimitExceededException.class)
    public void handleRateLimitException(ChatRateLimitExceededException ex, Principal principal) {
        log.warn("WS RateLimit: user={}, msg={}", principal != null ? principal.getName() : "unknown", ex.getMessage());
        if (principal != null) {
            ChatErrorResponse error = ChatErrorResponse.of("RATE_LIMITED", ex.getMessage(), null);
            messagingTemplate.convertAndSendToUser(principal.getName(), "/queue/errors", error);
        }
    }

    @MessageExceptionHandler(AppException.class)
    public void handleAppException(AppException ex, Principal principal) {
        log.warn("WS AppException: user={}, code={}, msg={}",
                principal != null ? principal.getName() : "unknown", ex.getErrorCode().name(), ex.getMessage());
        if (principal != null) {
            ChatErrorResponse error = ChatErrorResponse.of(ex.getErrorCode().name(), ex.getMessage(), null);
            messagingTemplate.convertAndSendToUser(principal.getName(), "/queue/errors", error);
        }
    }

    @MessageExceptionHandler(AccessDeniedException.class)
    public void handleAccessDeniedException(AccessDeniedException ex, Principal principal) {
        log.warn("WS Forbidden: user={}, msg={}", principal != null ? principal.getName() : "unknown", ex.getMessage());
        if (principal != null) {
            ChatErrorResponse error = ChatErrorResponse.of("FORBIDDEN", ex.getMessage(), null);
            messagingTemplate.convertAndSendToUser(principal.getName(), "/queue/errors", error);
        }
    }

    @MessageExceptionHandler(Exception.class)
    public void handleGenericException(Exception ex, Principal principal) {
        log.error("WS Generic error: user={}", principal != null ? principal.getName() : "unknown", ex);
        if (principal != null) {
            ChatErrorResponse error = ChatErrorResponse.of("INVALID_PAYLOAD", "Yêu cầu không hợp lệ hoặc đã xảy ra lỗi xử lý.", null);
            messagingTemplate.convertAndSendToUser(principal.getName(), "/queue/errors", error);
        }
    }
}

