package org.example.smashhub.chat.ai.vectorstore;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Document implements Serializable {
    private String id;
    private String content;
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
    private List<Double> embedding;
    private Double score; // Similarity score when returned from search

    public Document(String id, String content, Map<String, Object> metadata) {
        this.id = id;
        this.content = content;
        this.metadata = metadata != null ? metadata : new HashMap<>();
    }
}

