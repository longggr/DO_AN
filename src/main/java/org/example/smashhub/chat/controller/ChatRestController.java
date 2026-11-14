package org.example.smashhub.chat.controller;

import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.example.smashhub.chat.dto.request.ConversationFilterRequest;
import org.example.smashhub.chat.dto.request.InitConversationRequest;
import org.example.smashhub.chat.dto.response.ChatMessageResponse;
import org.example.smashhub.chat.dto.response.ConversationResponse;
import org.example.smashhub.chat.enums.ConversationStatus;
import org.example.smashhub.chat.service.ChatService;
import org.example.smashhub.common.dto.PageResponse;
import org.example.smashhub.common.response.ApiResponse;
import org.example.smashhub.file.dto.FileUploadResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ChatRestController {

    ChatService chatService;

    private String getCurrentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : null;
    }

    // 1. Khách hàng / Người dùng: Khởi tạo hoặc lấy hội thoại hiện tại
    @PostMapping("/conversations/init")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<ConversationResponse> initConversation(@RequestBody(required = false) InitConversationRequest request) {
        return ApiResponse.<ConversationResponse>builder()
                .result(chatService.initConversation(getCurrentUsername(), request))
                .build();
    }

    // 2. Khách hàng / Người dùng: Lấy thông tin hội thoại của mình
    @GetMapping("/conversations/me")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<ConversationResponse> getMyConversation() {
        return ApiResponse.<ConversationResponse>builder()
                .result(chatService.getMyConversation(getCurrentUsername()))
                .build();
    }

    // 3. Admin / Staff: Danh sách hội thoại phân trang & lọc
    @GetMapping("/admin/conversations")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ApiResponse<PageResponse<ConversationResponse>> getConversationsForAdmin(
            @RequestParam(required = false) ConversationStatus status,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        ConversationFilterRequest filter = ConversationFilterRequest.builder()
                .status(status)
                .unreadOnly(unreadOnly)
                .page(page)
                .size(size)
                .build();
        return ApiResponse.<PageResponse<ConversationResponse>>builder()
                .result(chatService.getConversationsForAdmin(filter))
                .build();
    }

    // 4. Admin / Staff: Nhận hoặc chuyển giao hội thoại cho nhân viên khác
    @PatchMapping("/admin/conversations/{id}/assign")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ApiResponse<ConversationResponse> assignConversation(
            @PathVariable Long id,
            @RequestParam(required = false) Long targetStaffId
    ) {
        return ApiResponse.<ConversationResponse>builder()
                .result(chatService.assignConversation(id, getCurrentUsername(), targetStaffId))
                .build();
    }

    // 5. Admin / Staff: Đóng hội thoại (RESOLVED)
    @PatchMapping("/admin/conversations/{id}/resolve")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ApiResponse<ConversationResponse> resolveConversation(@PathVariable Long id) {
        return ApiResponse.<ConversationResponse>builder()
                .result(chatService.resolveConversation(id, getCurrentUsername()))
                .build();
    }

    // 6. Admin / Staff: Mở lại hội thoại (OPEN)
    @PatchMapping("/admin/conversations/{id}/reopen")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ApiResponse<ConversationResponse> reopenConversation(@PathVariable Long id) {
        return ApiResponse.<ConversationResponse>builder()
                .result(chatService.reopenConversation(id, getCurrentUsername()))
                .build();
    }

    // 6b. Admin / Staff: Đổi chế độ xử lý hội thoại (AI | HUMAN)
    @PatchMapping("/admin/conversations/{id}/handler-mode")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ApiResponse<String> updateHandlerMode(
            @PathVariable Long id,
            @RequestParam org.example.smashhub.chat.enums.HandlerMode mode
    ) {
        return ApiResponse.<String>builder()
                .result(chatService.updateHandlerMode(id, mode))
                .build();
    }

    // 7. Lấy lịch sử tin nhắn trong hội thoại (phân trang, batch-fetch thẻ)
    @GetMapping("/conversations/{id}/messages")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<PageResponse<ChatMessageResponse>> getMessages(
            @PathVariable Long id,
            @PageableDefault(size = 20, sort = "createDate", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ApiResponse.<PageResponse<ChatMessageResponse>>builder()
                .result(chatService.getMessages(id, getCurrentUsername(), pageable))
                .build();
    }

    // 8. Upload ảnh đứt cước / bill chuyển khoản vào Cloudinary smashhub/chat
    @PostMapping(value = "/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<FileUploadResponse> uploadAttachment(@RequestParam("file") MultipartFile file) {
        return ApiResponse.<FileUploadResponse>builder()
                .result(chatService.uploadAttachment(getCurrentUsername(), file))
                .build();
    }

    // 9. Đánh giá chất lượng câu trả lời của Trợ lý AI (HELPFUL | NOT_HELPFUL)
    @PostMapping("/messages/{id}/feedback")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<ChatMessageResponse> submitFeedback(
            @PathVariable Long id,
            @RequestBody(required = false) org.example.smashhub.chat.dto.request.FeedbackRequest body,
            @RequestParam(required = false) org.example.smashhub.chat.enums.AiFeedback feedback
    ) {
        org.example.smashhub.chat.enums.AiFeedback targetFeedback = (body != null && body.getFeedback() != null)
                ? body.getFeedback()
                : feedback;

        if (targetFeedback == null) {
            targetFeedback = org.example.smashhub.chat.enums.AiFeedback.HELPFUL;
        }

        return ApiResponse.<ChatMessageResponse>builder()
                .result(chatService.submitMessageFeedback(id, getCurrentUsername(), targetFeedback))
                .build();
    }
}

