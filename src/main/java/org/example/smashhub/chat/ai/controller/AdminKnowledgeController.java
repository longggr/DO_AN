package org.example.smashhub.chat.ai.controller;

import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.example.smashhub.chat.ai.dto.KnowledgeDocumentRequest;
import org.example.smashhub.chat.ai.dto.KnowledgeDocumentResponse;
import org.example.smashhub.chat.ai.service.KnowledgeService;
import org.example.smashhub.common.dto.PageResponse;
import org.example.smashhub.common.response.ApiResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/knowledge")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AdminKnowledgeController {

    KnowledgeService knowledgeService;

    @PostMapping
    public ApiResponse<KnowledgeDocumentResponse> createDocument(
            @Valid @RequestBody KnowledgeDocumentRequest request) {
        return ApiResponse.<KnowledgeDocumentResponse>builder()
                .result(knowledgeService.createDocument(request))
                .build();
    }

    @GetMapping
    public ApiResponse<PageResponse<KnowledgeDocumentResponse>> getAllDocuments(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createDate").descending());
        Page<KnowledgeDocumentResponse> result = knowledgeService.getAllDocuments(status, pageable);

        PageResponse<KnowledgeDocumentResponse> response = PageResponse.<KnowledgeDocumentResponse>builder()
                .content(result.getContent())
                .pageNo(page)
                .pageSize(size)
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .isLast(result.isLast())
                .isFirst(result.isFirst())
                .build();

        return ApiResponse.<PageResponse<KnowledgeDocumentResponse>>builder()
                .result(response)
                .build();
    }

    @GetMapping("/{id}")
    public ApiResponse<KnowledgeDocumentResponse> getDocument(@PathVariable Long id) {
        return ApiResponse.<KnowledgeDocumentResponse>builder()
                .result(knowledgeService.getDocumentById(id))
                .build();
    }

    @PutMapping("/{id}")
    public ApiResponse<KnowledgeDocumentResponse> updateDocument(
            @PathVariable Long id,
            @Valid @RequestBody KnowledgeDocumentRequest request) {
        return ApiResponse.<KnowledgeDocumentResponse>builder()
                .result(knowledgeService.updateDocument(id, request))
                .build();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<String> deleteDocument(@PathVariable Long id) {
        knowledgeService.deleteDocument(id);
        return ApiResponse.<String>builder()
                .result("Knowledge document " + id + " deleted successfully")
                .build();
    }

    @PostMapping("/reindex")
    public ApiResponse<Map<String, Object>> reindexAll() {
        int chunksSynced = knowledgeService.reindexAll();
        return ApiResponse.<Map<String, Object>>builder()
                .result(Map.of(
                        "success", true,
                        "message", "Re-indexed active documents successfully",
                        "totalChunksSynced", chunksSynced
                ))
                .build();
    }
}
