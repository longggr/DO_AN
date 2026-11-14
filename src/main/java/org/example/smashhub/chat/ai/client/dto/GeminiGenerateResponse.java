package org.example.smashhub.chat.ai.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@FieldDefaults(level = AccessLevel.PRIVATE)
public class GeminiGenerateResponse {

    List<Candidate> candidates;
    UsageMetadata usageMetadata;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Candidate {
        private Content content;
        private String finishReason;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Content {
        private List<Part> parts;
        private String role;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Part {
        private String text;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UsageMetadata {
        private long promptTokenCount = 0L;
        private long candidatesTokenCount = 0L;
        private long totalTokenCount = 0L;
    }

    public String extractAnswerText() {
        if (candidates != null && !candidates.isEmpty()) {
            Candidate first = candidates.get(0);
            if (first.getContent() != null && first.getContent().getParts() != null) {
                StringBuilder sb = new StringBuilder();
                for (Part part : first.getContent().getParts()) {
                    if (part.getText() != null) {
                        sb.append(part.getText());
                    }
                }
                return sb.toString().trim();
            }
        }
        return "";
    }

    public static GeminiGenerateResponse ofText(String text) {
        Part part = new Part(text);
        Content content = new Content(List.of(part), "model");
        Candidate candidate = new Candidate(content, "STOP");
        UsageMetadata usage = new UsageMetadata(50L, 50L, 100L);
        return new GeminiGenerateResponse(List.of(candidate), usage);
    }
}

