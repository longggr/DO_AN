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
import java.util.Map;

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
    List<Tool> tools;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Content {
        private String role;
        private List<Part> parts;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Part {
        private String text;
        private FunctionCall functionCall;
        private FunctionResponse functionResponse;

        public static Part ofText(String text) {
            return Part.builder().text(text).build();
        }

        public static Part ofFunctionCall(String name, Map<String, Object> args) {
            return Part.builder()
                    .functionCall(new FunctionCall(name, args))
                    .build();
        }

        public static Part ofFunctionResponse(String name, Map<String, Object> response) {
            return Part.builder()
                    .functionResponse(new FunctionResponse(name, response))
                    .build();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FunctionCall {
        private String name;
        private Map<String, Object> args;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FunctionResponse {
        private String name;
        private Map<String, Object> response;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Tool {
        private List<FunctionDeclaration> functionDeclarations;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FunctionDeclaration {
        private String name;
        private String description;
        private Map<String, Object> parameters;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
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
                .parts(Collections.singletonList(Part.ofText(userPrompt)))
                .build();

        Content sysContent = (systemPrompt != null && !systemPrompt.isBlank())
                ? Content.builder()
                    .parts(Collections.singletonList(Part.ofText(systemPrompt)))
                    .build()
                : null;

        return GeminiGenerateRequest.builder()
                .systemInstruction(sysContent)
                .contents(Collections.singletonList(userContent))
                .generationConfig(GenerationConfig.builder().build())
                .build();
    }
}
