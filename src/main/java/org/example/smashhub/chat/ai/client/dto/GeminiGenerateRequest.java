package org.example.smashhub.chat.ai.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.util.Collections;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@FieldDefaults(level = AccessLevel.PRIVATE)
public class GeminiGenerateRequest {

    List<Content> contents;
    Content systemInstruction;
    GenerationConfig generationConfig;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Content {
        private String role;
        private List<Part> parts;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Part {
        private String text;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GenerationConfig {
        @Builder.Default
        private Double temperature = 0.7;
        @Builder.Default
        private Integer maxOutputTokens = 800;
        private Double topP;
        private Integer topK;
    }

    public static GeminiGenerateRequest of(String systemPrompt, String userPrompt) {
        Content userContent = Content.builder()
                .role("user")
                .parts(Collections.singletonList(Part.builder().text(userPrompt).build()))
                .build();

        Content sysContent = (systemPrompt != null && !systemPrompt.isBlank())
                ? Content.builder()
                    .parts(Collections.singletonList(Part.builder().text(systemPrompt).build()))
                    .build()
                : null;

        return GeminiGenerateRequest.builder()
                .systemInstruction(sysContent)
                .contents(Collections.singletonList(userContent))
                .generationConfig(GenerationConfig.builder().build())
                .build();
    }
}

