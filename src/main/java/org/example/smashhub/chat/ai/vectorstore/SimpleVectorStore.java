package org.example.smashhub.chat.ai.vectorstore;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.example.smashhub.chat.ai.service.AiEmbeddingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Triển khai VectorStore chuẩn Spring AI với file JSON persistence cho môi trường dev/local.
 * Trong môi trường production, cấu hình chuyển sang Redis Vector Search hoặc Qdrant
 * bằng cách kích hoạt Spring Profile (ví dụ: spring.profiles.active=redis-vector).
 */
@Component
@Primary
@Slf4j
public class SimpleVectorStore implements VectorStore {

    private final AiEmbeddingService embeddingService;
    private final ObjectMapper objectMapper;
    private final String persistenceFilePath;
    private final Map<String, Document> store = new ConcurrentHashMap<>();

    public SimpleVectorStore(
            AiEmbeddingService embeddingService,
            ObjectMapper objectMapper,
            @Value("${smashhub.ai.vectorstore.file-path:data/vectorstore.json}") String persistenceFilePath) {
        this.embeddingService = embeddingService;
        this.objectMapper = objectMapper;
        this.persistenceFilePath = persistenceFilePath;
    }

    @PostConstruct
    public void init() {
        loadFromFile();
    }

    @Override
    public synchronized void add(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }

        for (Document doc : documents) {
            if (doc.getEmbedding() == null || doc.getEmbedding().isEmpty()) {
                List<Double> embedding = embeddingService.getEmbedding(doc.getContent());
                doc.setEmbedding(embedding);
            }
            if (doc.getEmbedding() != null) {
                store.put(doc.getId(), doc);
            }
        }
        saveToFile();
    }

    @Override
    public synchronized void delete(List<String> idList) {
        if (idList == null || idList.isEmpty()) {
            return;
        }
        for (String id : idList) {
            store.remove(id);
        }
        saveToFile();
    }

    @Override
    public List<Document> similaritySearch(String query) {
        return similaritySearch(SearchRequest.query(query));
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        if (request == null || request.getQuery() == null || request.getQuery().isBlank()) {
            return Collections.emptyList();
        }

        List<Double> queryEmbedding = embeddingService.getEmbedding(request.getQuery());
        if (queryEmbedding == null || queryEmbedding.isEmpty()) {
            log.warn("Could not generate query embedding for query='{}'", request.getQuery());
            return Collections.emptyList();
        }

        List<Document> scoredDocs = new ArrayList<>();
        double threshold = request.getSimilarityThreshold();

        for (Document doc : store.values()) {
            if (doc.getEmbedding() == null) continue;

            // Optional category filter
            if (request.getFilter() != null && !request.getFilter().isEmpty()) {
                boolean match = true;
                for (Map.Entry<String, Object> entry : request.getFilter().entrySet()) {
                    Object metaVal = doc.getMetadata().get(entry.getKey());
                    if (metaVal == null || !metaVal.toString().equalsIgnoreCase(entry.getValue().toString())) {
                        match = false;
                        break;
                    }
                }
                if (!match) continue;
            }

            double score = cosineSimilarity(queryEmbedding, doc.getEmbedding());
            if (score >= threshold) {
                Document copy = Document.builder()
                        .id(doc.getId())
                        .content(doc.getContent())
                        .metadata(doc.getMetadata())
                        .embedding(doc.getEmbedding())
                        .score(score)
                        .build();
                scoredDocs.add(copy);
            }
        }

        return scoredDocs.stream()
                .sorted(Comparator.comparingDouble(Document::getScore).reversed())
                .limit(request.getTopK())
                .collect(Collectors.toList());
    }

    public int size() {
        return store.size();
    }

    public void clear() {
        store.clear();
        saveToFile();
    }

    private double cosineSimilarity(List<Double> vecA, List<Double> vecB) {
        if (vecA == null || vecB == null || vecA.size() != vecB.size() || vecA.isEmpty()) {
            return 0.0;
        }

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < vecA.size(); i++) {
            double a = vecA.get(i);
            double b = vecB.get(i);
            dotProduct += a * b;
            normA += a * a;
            normB += b * b;
        }

        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private void saveToFile() {
        try {
            Path path = Paths.get(persistenceFilePath);
            if (path.getParent() != null && !Files.exists(path.getParent())) {
                Files.createDirectories(path.getParent());
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(persistenceFilePath), store);
            log.debug("Saved {} documents to vector store file: {}", store.size(), persistenceFilePath);
        } catch (IOException e) {
            log.error("Failed to save vector store to file: {}", persistenceFilePath, e);
        }
    }

    private void loadFromFile() {
        File file = new File(persistenceFilePath);
        if (!file.exists()) {
            log.info("No existing vector store file at {}. Starting fresh.", persistenceFilePath);
            return;
        }
        try {
            Map<String, Document> loaded = objectMapper.readValue(file, new TypeReference<Map<String, Document>>() {});
            if (loaded != null) {
                store.putAll(loaded);
                log.info("Loaded {} documents from vector store file: {}", store.size(), persistenceFilePath);
            }
        } catch (IOException e) {
            log.error("Failed to load vector store from file: {}", persistenceFilePath, e);
        }
    }
}

