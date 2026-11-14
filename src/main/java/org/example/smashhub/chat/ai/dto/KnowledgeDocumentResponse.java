package org.example.smashhub.chat.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocumentResponse {
    private Long id;
    private String title;
    private String category;
    private String contentMarkdown;
    private String contentHash;
    private String status;
    private int chunksCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

