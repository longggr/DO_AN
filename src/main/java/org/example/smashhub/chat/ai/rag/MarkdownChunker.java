package org.example.smashhub.chat.ai.rag;

import lombok.extern.slf4j.Slf4j;
import org.example.smashhub.chat.ai.vectorstore.Document;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class MarkdownChunker {

    private static final int TARGET_CHUNK_MIN_CHARS = 1000;  // ~250-300 tokens
    private static final int TARGET_CHUNK_MAX_CHARS = 1800;  // ~450-500 tokens
    private static final int OVERLAP_CHARS = 150;            // ~40 tokens gối đầu

    public List<Document> chunkMarkdown(Long documentId, String title, String category, String markdown) {
        List<Document> chunks = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            return chunks;
        }

        String[] lines = markdown.split("\\r?\\n");
        List<String> currentBreadcrumbs = new ArrayList<>();
        StringBuilder currentSection = new StringBuilder();
        int chunkIndex = 0;

        for (String rawLine : lines) {
            String line = rawLine.trim();

            // Nhận diện heading markdown (#, ##, ###, ####)
            if (line.startsWith("#")) {
                int level = 0;
                while (level < line.length() && line.charAt(level) == '#') {
                    level++;
                }
                String headingTitle = line.substring(level).trim();

                // Nếu đoạn hiện tại đã đủ dung lượng, tạo chunk trước khi đổi section
                if (currentSection.length() >= TARGET_CHUNK_MIN_CHARS) {
                    Document doc = createChunkDocument(documentId, title, category, chunkIndex++,
                            buildBreadcrumb(title, currentBreadcrumbs), currentSection.toString());
                    chunks.add(doc);

                    // Gối đầu nhẹ (overlap) cho đoạn tiếp theo
                    String overlap = extractOverlap(currentSection.toString());
                    currentSection.setLength(0);
                    currentSection.append(overlap);
                }

                // Cập nhật cấu trúc phân cấp tiêu đề
                while (currentBreadcrumbs.size() >= level) {
                    currentBreadcrumbs.remove(currentBreadcrumbs.size() - 1);
                }
                currentBreadcrumbs.add(headingTitle);
                currentSection.append("\n").append(line).append("\n");

            } else {
                currentSection.append(rawLine).append("\n");

                // Nếu nội dung vượt quá kích thước tối đa cho phép
                if (currentSection.length() >= TARGET_CHUNK_MAX_CHARS) {
                    Document doc = createChunkDocument(documentId, title, category, chunkIndex++,
                            buildBreadcrumb(title, currentBreadcrumbs), currentSection.toString());
                    chunks.add(doc);

                    String overlap = extractOverlap(currentSection.toString());
                    currentSection.setLength(0);
                    currentSection.append(overlap);
                }
            }
        }

        // Tạo chunk cho phần còn lại
        if (currentSection.length() > 0 && !currentSection.toString().isBlank()) {
            Document doc = createChunkDocument(documentId, title, category, chunkIndex++,
                    buildBreadcrumb(title, currentBreadcrumbs), currentSection.toString());
            chunks.add(doc);
        }

        return chunks;
    }

    private Document createChunkDocument(Long documentId, String title, String category, int chunkIndex,
                                         String breadcrumb, String contentText) {
        String trimmed = contentText.trim();
        String formattedContent = String.format("[Tài liệu: %s | Mục: %s]\n%s", title, breadcrumb, trimmed);
        String hash = computeSha256(formattedContent);

        String docId = String.format("doc_%d_chunk_%d", documentId != null ? documentId : 0L, chunkIndex);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("documentId", documentId);
        metadata.put("title", title);
        metadata.put("category", category);
        metadata.put("chunkIndex", chunkIndex);
        metadata.put("heading", breadcrumb);
        metadata.put("contentHash", hash);

        return Document.builder()
                .id(docId)
                .content(formattedContent)
                .metadata(metadata)
                .build();
    }

    private String buildBreadcrumb(String title, List<String> breadcrumbs) {
        if (breadcrumbs == null || breadcrumbs.isEmpty()) {
            return title;
        }
        return String.join(" > ", breadcrumbs);
    }

    private String extractOverlap(String text) {
        if (text.length() <= OVERLAP_CHARS) {
            return "";
        }
        int cutIndex = text.length() - OVERLAP_CHARS;
        int nextSpace = text.indexOf(' ', cutIndex);
        if (nextSpace != -1 && nextSpace < text.length() - 20) {
            cutIndex = nextSpace + 1;
        }
        return text.substring(cutIndex);
    }

    public String computeSha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not found", e);
        }
    }
}

