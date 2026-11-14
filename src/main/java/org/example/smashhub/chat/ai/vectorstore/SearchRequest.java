package org.example.smashhub.chat.ai.vectorstore;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchRequest {
    private String query;
    @Builder.Default
    private int topK = 4;
    @Builder.Default
    private double similarityThreshold = 0.65;
    private Map<String, Object> filter;

    public static SearchRequest query(String query) {
        return SearchRequest.builder().query(query).build();
    }

    public SearchRequest withTopK(int topK) {
        this.topK = topK;
        return this;
    }

    public SearchRequest withSimilarityThreshold(double similarityThreshold) {
        this.similarityThreshold = similarityThreshold;
        return this;
    }
}

