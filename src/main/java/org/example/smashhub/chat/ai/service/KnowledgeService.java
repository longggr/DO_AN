package org.example.smashhub.chat.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.example.smashhub.chat.ai.dto.KnowledgeDocumentRequest;
import org.example.smashhub.chat.ai.dto.KnowledgeDocumentResponse;
import org.example.smashhub.chat.ai.entity.AiKnowledgeChunk;
import org.example.smashhub.chat.ai.entity.KnowledgeDocument;
import org.example.smashhub.chat.ai.rag.MarkdownChunker;
import org.example.smashhub.chat.ai.repository.AiKnowledgeChunkRepository;
import org.example.smashhub.chat.ai.repository.KnowledgeDocumentRepository;
import org.example.smashhub.chat.ai.vectorstore.Document;
import org.example.smashhub.chat.ai.vectorstore.VectorStore;
import org.example.smashhub.exception.AppException;
import org.example.smashhub.exception.ErrorCode;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class KnowledgeService {

    KnowledgeDocumentRepository documentRepository;
    AiKnowledgeChunkRepository chunkRepository;
    MarkdownChunker markdownChunker;
    VectorStore vectorStore;
    AiEmbeddingService embeddingService;
    ObjectMapper objectMapper;

    @Transactional
    public KnowledgeDocumentResponse createDocument(KnowledgeDocumentRequest request) {
        String contentHash = markdownChunker.computeSha256(request.getContentMarkdown().trim());

        KnowledgeDocument doc = KnowledgeDocument.builder()
                .title(request.getTitle())
                .category(request.getCategory() != null ? request.getCategory() : "GENERAL")
                .contentMarkdown(request.getContentMarkdown())
                .contentHash(contentHash)
                .status(request.getStatus() != null ? request.getStatus() : "ACTIVE")
                .build();

        KnowledgeDocument saved = documentRepository.save(doc);

        // Chunks & embeddings
        List<Document> chunks = ingestChunksForDocument(saved);

        return toResponse(saved, chunks.size());
    }

    @Transactional
    public KnowledgeDocumentResponse updateDocument(Long id, KnowledgeDocumentRequest request) {
        KnowledgeDocument doc = documentRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));

        String newContentHash = markdownChunker.computeSha256(request.getContentMarkdown().trim());
        boolean contentChanged = !newContentHash.equals(doc.getContentHash());

        doc.setTitle(request.getTitle());
        if (request.getCategory() != null) {
            doc.setCategory(request.getCategory());
        }
        if (request.getStatus() != null) {
            doc.setStatus(request.getStatus());
        }
        doc.setContentMarkdown(request.getContentMarkdown());
        doc.setContentHash(newContentHash);

        KnowledgeDocument saved = documentRepository.save(doc);

        List<Document> currentChunks;
        if (contentChanged) {
            // Re-index delta: chỉ embed chunk có hash thay đổi, xoá vector của chunk không còn tồn tại
            currentChunks = syncChunksDelta(saved);
        } else {
            currentChunks = chunkRepository.findByEntityTypeAndEntityId("POLICY", saved.getId()).stream()
                    .map(this::toVectorDocument)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }

        return toResponse(saved, currentChunks.size());
    }

    @Transactional
    public void deleteDocument(Long id) {
        KnowledgeDocument doc = documentRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));

        // Xoá chunks khỏi vector store và DB
        List<AiKnowledgeChunk> existingChunks = chunkRepository.findByEntityTypeAndEntityId("POLICY", id);
        List<String> vectorIds = existingChunks.stream()
                .map(c -> "doc_" + id + "_chunk_" + c.getId())
                .collect(Collectors.toList());

        vectorStore.delete(vectorIds);
        chunkRepository.deleteAll(existingChunks);
        documentRepository.delete(doc);
        log.info("Deleted knowledge document id={} and removed {} vector chunks", id, vectorIds.size());
    }

    public KnowledgeDocumentResponse getDocumentById(Long id) {
        KnowledgeDocument doc = documentRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
        List<AiKnowledgeChunk> chunks = chunkRepository.findByEntityTypeAndEntityId("POLICY", id);
        return toResponse(doc, chunks.size());
    }

    public Page<KnowledgeDocumentResponse> getAllDocuments(String status, Pageable pageable) {
        Page<KnowledgeDocument> page = (status == null || status.isBlank())
                ? documentRepository.findAll(pageable)
                : documentRepository.findByStatusOrderByCreateDateDesc(status, pageable);

        return page.map(doc -> {
            List<AiKnowledgeChunk> chunks = chunkRepository.findByEntityTypeAndEntityId("POLICY", doc.getId());
            return toResponse(doc, chunks.size());
        });
    }

    @Transactional
    public int reindexAll() {
        log.info("Starting re-indexing of all active knowledge documents...");
        List<KnowledgeDocument> activeDocs = documentRepository.findByStatus("ACTIVE");
        int totalChunks = 0;
        for (KnowledgeDocument doc : activeDocs) {
            List<Document> chunks = syncChunksDelta(doc);
            totalChunks += chunks.size();
        }
        log.info("Completed re-indexing {} active documents, total {} chunks synced", activeDocs.size(), totalChunks);
        return totalChunks;
    }

    /**
     * Chunk markdown và đồng bộ delta:
     * - Chỉ re-embed chunk có hash thay đổi.
     * - Xóa vector của chunk không còn tồn tại.
     */
    private List<Document> syncChunksDelta(KnowledgeDocument doc) {
        List<Document> newChunks = markdownChunker.chunkMarkdown(
                doc.getId(), doc.getTitle(), doc.getCategory(), doc.getContentMarkdown());

        List<AiKnowledgeChunk> oldChunks = chunkRepository.findByEntityTypeAndEntityId("POLICY", doc.getId());
        Map<String, AiKnowledgeChunk> oldChunkByHash = oldChunks.stream()
                .collect(Collectors.toMap(AiKnowledgeChunk::getContentHash, c -> c, (a, b) -> a));

        List<Document> docsToVectorStore = new ArrayList<>();
        List<AiKnowledgeChunk> chunksToSave = new ArrayList<>();
        List<String> idsToDelete = new ArrayList<>();

        Map<String, Boolean> seenOldHashes = new HashMap<>();

        for (int i = 0; i < newChunks.size(); i++) {
            Document chunkDoc = newChunks.get(i);
            String chunkHash = (String) chunkDoc.getMetadata().get("contentHash");

            if (oldChunkByHash.containsKey(chunkHash)) {
                // HASH KHÔNG ĐỔI -> Tái sử dụng embedding cũ, không tốn quota gọi lại API
                AiKnowledgeChunk existing = oldChunkByHash.get(chunkHash);
                seenOldHashes.put(chunkHash, true);
                chunkDoc.setEmbedding(parseEmbeddingJson(existing.getEmbeddingJson()));
                docsToVectorStore.add(chunkDoc);
            } else {
                // HASH THAY ĐỔI / CHUNK MỚI -> Gọi Embedding API
                List<Double> embedding = embeddingService.getEmbedding(chunkDoc.getContent());
                chunkDoc.setEmbedding(embedding);
                docsToVectorStore.add(chunkDoc);

                AiKnowledgeChunk newDbChunk = AiKnowledgeChunk.builder()
                        .entityType("POLICY")
                        .entityId(doc.getId())
                        .contentHash(chunkHash)
                        .chunkText(chunkDoc.getContent())
                        .embeddingJson(serializeEmbeddingJson(embedding))
                        .status("ACTIVE")
                        .build();
                chunksToSave.add(newDbChunk);
            }
        }

        // Tìm các chunk cũ không còn tồn tại trong nội dung mới để xóa
        for (AiKnowledgeChunk oldChunk : oldChunks) {
            if (!seenOldHashes.containsKey(oldChunk.getContentHash())) {
                idsToDelete.add("doc_" + doc.getId() + "_chunk_" + oldChunk.getId());
                chunkRepository.delete(oldChunk);
            }
        }

        if (!idsToDelete.isEmpty()) {
            vectorStore.delete(idsToDelete);
            log.info("Deleted {} obsolete vector chunks for docId={}", idsToDelete.size(), doc.getId());
        }

        if (!chunksToSave.isEmpty()) {
            chunkRepository.saveAll(chunksToSave);
            log.info("Saved {} newly embedded chunks for docId={}", chunksToSave.size(), doc.getId());
        }

        vectorStore.add(docsToVectorStore);
        return docsToVectorStore;
    }

    private List<Document> ingestChunksForDocument(KnowledgeDocument doc) {
        List<Document> chunks = markdownChunker.chunkMarkdown(
                doc.getId(), doc.getTitle(), doc.getCategory(), doc.getContentMarkdown());

        List<AiKnowledgeChunk> dbChunks = new ArrayList<>();
        for (Document chunk : chunks) {
            List<Double> embedding = embeddingService.getEmbedding(chunk.getContent());
            chunk.setEmbedding(embedding);

            String hash = (String) chunk.getMetadata().get("contentHash");
            AiKnowledgeChunk dbChunk = AiKnowledgeChunk.builder()
                    .entityType("POLICY")
                    .entityId(doc.getId())
                    .contentHash(hash)
                    .chunkText(chunk.getContent())
                    .embeddingJson(serializeEmbeddingJson(embedding))
                    .status("ACTIVE")
                    .build();
            dbChunks.add(dbChunk);
        }

        chunkRepository.saveAll(dbChunks);
        vectorStore.add(chunks);
        return chunks;
    }

    /**
     * Nạp tự động các tài liệu mẫu Markdown từ classpath:knowledge/*.md nếu database chưa có tài liệu nào.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void seedKnowledgeFilesIfEmpty() {
        try {
            if (documentRepository.count() > 0) {
                log.info("Knowledge base already contains {} documents. Skipping automatic seed.", documentRepository.count());
                return;
            }

            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:knowledge/*.md");
            log.info("Found {} sample knowledge markdown files in classpath:knowledge/", resources.length);

            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null) continue;

                String content = resource.getContentAsString(StandardCharsets.UTF_8);
                String title = extractTitleFromMarkdown(filename, content);
                String category = mapCategoryFromFilename(filename);

                KnowledgeDocumentRequest req = KnowledgeDocumentRequest.builder()
                        .title(title)
                        .category(category)
                        .contentMarkdown(content)
                        .status("ACTIVE")
                        .build();

                createDocument(req);
                log.info("Seeded knowledge document: '{}' (category={})", title, category);
            }
        } catch (Exception e) {
            log.warn("Failed to automatically seed knowledge files from classpath:knowledge/", e);
        }
    }

    private String extractTitleFromMarkdown(String filename, String content) {
        for (String line : content.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ")) {
                return trimmed.substring(2).trim();
            }
        }
        return filename.replace(".md", "").replace("-", " ");
    }

    private String mapCategoryFromFilename(String filename) {
        String lower = filename.toLowerCase();
        if (lower.contains("bao-hanh")) return "WARRANTY";
        if (lower.contains("doi-tra")) return "RETURN";
        if (lower.contains("van-chuyen")) return "SHIPPING";
        if (lower.contains("thanh-toan")) return "PAYMENT";
        if (lower.contains("vot")) return "RACKET_GUIDE";
        if (lower.contains("cuoc")) return "STRING_GUIDE";
        if (lower.contains("giay")) return "SHOE_GUIDE";
        return "GENERAL";
    }

    private Document toVectorDocument(AiKnowledgeChunk chunk) {
        if (chunk == null) return null;
        return Document.builder()
                .id("doc_" + chunk.getEntityId() + "_chunk_" + chunk.getId())
                .content(chunk.getChunkText())
                .embedding(parseEmbeddingJson(chunk.getEmbeddingJson()))
                .build();
    }

    private String serializeEmbeddingJson(List<Double> embedding) {
        if (embedding == null) return null;
        try {
            return objectMapper.writeValueAsString(embedding);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private List<Double> parseEmbeddingJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<List<Double>>() {});
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private KnowledgeDocumentResponse toResponse(KnowledgeDocument doc, int chunksCount) {
        return KnowledgeDocumentResponse.builder()
                .id(doc.getId())
                .title(doc.getTitle())
                .category(doc.getCategory())
                .contentMarkdown(doc.getContentMarkdown())
                .contentHash(doc.getContentHash())
                .status(doc.getStatus())
                .chunksCount(chunksCount)
                .createdAt(doc.getCreateDate())
                .updatedAt(doc.getUpdateDate())
                .build();
    }
}

