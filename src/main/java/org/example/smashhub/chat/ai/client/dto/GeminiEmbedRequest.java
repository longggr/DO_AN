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
public class GeminiEmbedRequest {

    String model;
    Content content;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Content {
        private List<Part> parts;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Part {
        private String text;
    }

    public static GeminiEmbedRequest of(String modelPath, String text) {
        return GeminiEmbedRequest.builder()
                .model(modelPath)
                .content(Content.builder()
                        .parts(Collections.singletonList(Part.builder().text(text).build()))
                        .build())
                .build();
    }
}

