package org.example.smashhub.chat.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.example.smashhub.catalog.entity.Product;
import org.example.smashhub.catalog.entity.ProductAttribute;
import org.example.smashhub.catalog.repository.ProductAttributeRepository;
import org.example.smashhub.catalog.repository.ProductRepository;
import org.example.smashhub.chat.ai.entity.AiKnowledgeChunk;
import org.example.smashhub.chat.ai.rag.ProductDocumentBuilder;
import org.example.smashhub.chat.ai.repository.AiKnowledgeChunkRepository;
import org.example.smashhub.chat.ai.vectorstore.Document;
import org.example.smashhub.chat.ai.vectorstore.VectorStore;
import org.example.smashhub.common.enums.ProductStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ProductIndexingService {

    ProductRepository productRepository;
    ProductAttributeRepository productAttributeRepository;
    ProductDocumentBuilder productDocumentBuilder;
    VectorStore vectorStore;
    AiEmbeddingService embeddingService;
    AiKnowledgeChunkRepository chunkRepository;
    ObjectMapper objectMapper;

    /**
     * Đồng bộ embedding cho một sản phẩm:
     * - Nếu sản phẩm không tồn tại hoặc không ACTIVE -> gỡ khỏi vector store.
     * - Nếu hash nội dung không đổi -> bỏ qua embedding (tiết kiệm quota).
     * - Nếu có thay đổi -> tính embedding mới và cập nhật VectorStore.
     */
    @Transactional
    public void indexProduct(Long productId) {
        if (productId == null) return;

        Optional<Product> optProduct = productRepository.findById(productId);
        if (optProduct.isEmpty() || optProduct.get().getProductStatus() != ProductStatus.ACTIVE) {
            removeProduct(productId);
            return;
        }

        Product product = optProduct.get();
        List<ProductAttribute> attributes = productAttributeRepository.findByProductId(productId);
        Document doc = productDocumentBuilder.buildDocument(product, attributes);
        if (doc == null) return;

        String contentHash = (String) doc.getMetadata().get("contentHash");

        // So sánh mã băm trong DB (Hash-skipping)
        List<AiKnowledgeChunk> existingChunks = chunkRepository.findByEntityTypeAndEntityId("PRODUCT", productId);
        if (!existingChunks.isEmpty()) {
            AiKnowledgeChunk first = existingChunks.get(0);
            if (contentHash.equals(first.getContentHash())) {
                log.debug("Product id={} content hash unchanged ({}). Skipping re-embedding.", productId, contentHash);
                // Đảm bảo document vẫn có trong in-memory vector store nếu chưa nạp
                doc.setEmbedding(parseEmbeddingJson(first.getEmbeddingJson()));
                vectorStore.add(List.of(doc));
                return;
            }
        }

        // Nội dung mới hoặc đã thay đổi -> gọi embedding
        log.info("Generating embedding for product id={} (name='{}')", productId, product.getName());
        List<Double> embedding = embeddingService.getEmbedding(doc.getContent());
        doc.setEmbedding(embedding);

        // Lưu vào VectorStore
        vectorStore.add(List.of(doc));

        // Cập nhật hoặc lưu mới bảng ai_knowledge_chunks
        chunkRepository.deleteByEntityTypeAndEntityId("PRODUCT", productId);
        AiKnowledgeChunk dbChunk = AiKnowledgeChunk.builder()
                .entityType("PRODUCT")
                .entityId(productId)
                .contentHash(contentHash)
                .chunkText(doc.getContent())
                .embeddingJson(serializeEmbeddingJson(embedding))
                .status("ACTIVE")
                .build();
        chunkRepository.save(dbChunk);
        log.info("Successfully indexed product id={} into vector store", productId);
    }

    @Transactional
    public void removeProduct(Long productId) {
        if (productId == null) return;
        vectorStore.delete(List.of("prod_" + productId));
        chunkRepository.deleteByEntityTypeAndEntityId("PRODUCT", productId);
        log.info("Removed product id={} from vector store and chunk repository", productId);
    }

    /**
     * Job đồng bộ định kỳ ban đêm (lưới an toàn) đảm bảo không có sản phẩm nào bị lệch chỉ mục.
     */
    @Scheduled(cron = "${smashhub.ai.sync.product-cron:0 0 2 * * ?}")
    @Transactional
    public void syncAllActiveProductsNightly() {
        log.info("Starting nightly scheduled product vector sync...");
        List<Product> allProducts = productRepository.findAll();
        int activeCount = 0;
        int removedCount = 0;

        for (Product product : allProducts) {
            if (product.getProductStatus() == ProductStatus.ACTIVE) {
                indexProduct(product.getId());
                activeCount++;
            } else {
                removeProduct(product.getId());
                removedCount++;
            }
        }
        log.info("Nightly product sync completed: {} active indexed, {} non-active removed", activeCount, removedCount);
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
}

