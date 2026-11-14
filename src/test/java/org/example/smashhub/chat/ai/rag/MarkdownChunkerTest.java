package org.example.smashhub.chat.ai.rag;

import org.example.smashhub.chat.ai.vectorstore.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownChunkerTest {

    private MarkdownChunker chunker;

    @BeforeEach
    void setUp() {
        chunker = new MarkdownChunker();
    }

    @Test
    @DisplayName("Chunking markdown phân tách theo heading và duy trì tiêu đề mục cha (breadcrumb)")
    void testChunkingPreservesHeadingBreadcrumbs() {
        String markdown = """
                # Chính sách đổi trả SmashHub
                
                ## 1. Thời hạn áp dụng
                Khách hàng được quyền đổi sản phẩm trong 7 ngày kể từ khi nhận hàng.
                Đơn vị vận chuyển giao tới có biên nhận rõ ràng.
                
                ## 2. Điều kiện đổi trả
                Sản phẩm phải còn nguyên tem mác, nguyên hộp, chưa qua sử dụng.
                Đối với vợt cầu lông: chưa căng cước, chưa bóc seal cán gỗ.
                """;

        List<Document> chunks = chunker.chunkMarkdown(1L, "Chính sách đổi trả", "POLICY", markdown);

        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());

        Document firstChunk = chunks.get(0);
        assertEquals(1L, firstChunk.getMetadata().get("documentId"));
        assertEquals("POLICY", firstChunk.getMetadata().get("category"));
        assertNotNull(firstChunk.getMetadata().get("contentHash"));
        assertTrue(firstChunk.getContent().contains("[Tài liệu:"));
    }

    @Test
    @DisplayName("Tính toán contentHash chính xác bằng thuật toán SHA-256")
    void testSha256Calculation() {
        String text = "SmashHub chuyên cung cấp vợt cầu lông chính hãng";
        String hash1 = chunker.computeSha256(text);
        String hash2 = chunker.computeSha256(text);
        String hashDifferent = chunker.computeSha256(text + " cao cấp");

        assertNotNull(hash1);
        assertEquals(64, hash1.length());
        assertEquals(hash1, hash2);
        assertNotEquals(hash1, hashDifferent);
    }
}

