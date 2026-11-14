package org.example.smashhub.chat.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.smashhub.chat.ai.dto.KnowledgeDocumentRequest;
import org.example.smashhub.chat.ai.dto.KnowledgeDocumentResponse;
import org.example.smashhub.chat.ai.entity.AiKnowledgeChunk;
import org.example.smashhub.chat.ai.entity.KnowledgeDocument;
import org.example.smashhub.chat.ai.rag.MarkdownChunker;
import org.example.smashhub.chat.ai.repository.AiKnowledgeChunkRepository;
import org.example.smashhub.chat.ai.repository.KnowledgeDocumentRepository;
import org.example.smashhub.chat.ai.vectorstore.Document;
import org.example.smashhub.chat.ai.vectorstore.VectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KnowledgeServiceTest {

    @Mock
    KnowledgeDocumentRepository documentRepository;
    @Mock
    AiKnowledgeChunkRepository chunkRepository;
    @Mock
    VectorStore vectorStore;
    @Mock
    AiEmbeddingService embeddingService;
    @Spy
    MarkdownChunker markdownChunker = new MarkdownChunker();
    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    KnowledgeService knowledgeService;

    KnowledgeDocument sampleDoc;

    @BeforeEach
    void setUp() {
        sampleDoc = KnowledgeDocument.builder()
                .title("Chính sách bảo hành")
                .category("WARRANTY")
                .contentMarkdown("# Bảo hành vợt\nBảo hành 90 ngày cho các lỗi nhà sản xuất.")
                .contentHash("initial_hash_123")
                .status("ACTIVE")
                .build();
        sampleDoc.setId(10L);
    }

    @Test
    @DisplayName("Delta Re-indexing: Bỏ qua re-embed cho các chunk có hash không đổi (Hash-skipping)")
    void testDeltaSyncSkipsUnchangedChunks() {
        when(documentRepository.findById(10L)).thenReturn(Optional.of(sampleDoc));
        when(documentRepository.save(any(KnowledgeDocument.class))).thenReturn(sampleDoc);

        // Giả lập chunk cũ đã có trong DB với hash trùng khớp
        List<Document> simulatedChunks = markdownChunker.chunkMarkdown(10L, sampleDoc.getTitle(), sampleDoc.getCategory(), sampleDoc.getContentMarkdown());
        String chunkHash = (String) simulatedChunks.get(0).getMetadata().get("contentHash");

        AiKnowledgeChunk existingDbChunk = AiKnowledgeChunk.builder()
                .entityType("POLICY")
                .entityId(10L)
                .contentHash(chunkHash)
                .chunkText(simulatedChunks.get(0).getContent())
                .embeddingJson("[0.12, 0.34, 0.56]")
                .status("ACTIVE")
                .build();
        existingDbChunk.setId(100L);

        when(chunkRepository.findByEntityTypeAndEntityId("POLICY", 10L)).thenReturn(List.of(existingDbChunk));

        // Update với nội dung tương tự (không làm thay đổi hash của chunk)
        KnowledgeDocumentRequest req = KnowledgeDocumentRequest.builder()
                .title(sampleDoc.getTitle())
                .category("WARRANTY")
                .contentMarkdown(sampleDoc.getContentMarkdown())
                .status("ACTIVE")
                .build();

        KnowledgeDocumentResponse response = knowledgeService.updateDocument(10L, req);

        assertNotNull(response);
        // Xác minh embedding API KHÔNG bị gọi vì hash không đổi
        verify(embeddingService, never()).getEmbedding(anyString());
        verify(vectorStore).add(anyList());
    }

    @Test
    @DisplayName("Xóa tài liệu thì tự động xóa các vector chunks tương ứng khỏi VectorStore")
    void testDeleteDocumentRemovesVectors() {
        when(documentRepository.findById(10L)).thenReturn(Optional.of(sampleDoc));

        AiKnowledgeChunk chunk = AiKnowledgeChunk.builder()
                .entityType("POLICY")
                .entityId(10L)
                .contentHash("hash123")
                .chunkText("sample")
                .build();
        chunk.setId(55L);

        when(chunkRepository.findByEntityTypeAndEntityId("POLICY", 10L)).thenReturn(List.of(chunk));

        knowledgeService.deleteDocument(10L);

        verify(vectorStore).delete(eq(List.of("doc_10_chunk_55")));
        verify(chunkRepository).deleteAll(anyList());
        verify(documentRepository).delete(eq(sampleDoc));
    }
}
