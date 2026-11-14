package org.example.smashhub.chat.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocumentRequest {
    @NotBlank(message = "Tiêu đề không được để trống")
    private String title;

    private String category;

    @NotBlank(message = "Nội dung Markdown không được để trống")
    private String contentMarkdown;

    private String status;
}

