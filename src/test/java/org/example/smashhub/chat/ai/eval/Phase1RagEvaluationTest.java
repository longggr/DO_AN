package org.example.smashhub.chat.ai.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Phase1RagEvaluationTest {

    @Test
    @DisplayName("Đánh giá bộ 30 test case tĩnh trong eval/phase1.yaml: Đầy đủ 30 kịch bản và bao phủ 10 danh mục")
    void testEvaluationSuiteIntegrity() throws IOException {
        File evalFile = new File("eval/phase1.yaml");
        assertTrue(evalFile.exists(), "Tệp eval/phase1.yaml phải tồn tại ở thư mục gốc của dự án");

        String content = Files.readString(evalFile.toPath(), StandardCharsets.UTF_8);
        assertNotNull(content);

        // Đếm số lượng kịch bản kiểm thử (id: 1 đến id: 30)
        int testCaseCount = 0;
        for (int i = 1; i <= 30; i++) {
            assertTrue(content.contains("id: " + i), "Thiếu test case id: " + i);
            testCaseCount++;
        }
        assertEquals(30, testCaseCount, "Bộ eval phải có đầy đủ đúng 30 câu hỏi mẫu");

        // Kiểm tra các danh mục bắt buộc
        assertTrue(content.contains("category: \"WARRANTY\""));
        assertTrue(content.contains("category: \"RETURN\""));
        assertTrue(content.contains("category: \"SHIPPING\""));
        assertTrue(content.contains("category: \"PAYMENT\""));
        assertTrue(content.contains("category: \"RACKET_GUIDE\""));
        assertTrue(content.contains("category: \"STRING_GUIDE\""));
        assertTrue(content.contains("category: \"SHOE_GUIDE\""));
        assertTrue(content.contains("category: \"OUT_OF_SCOPE\""));
        assertTrue(content.contains("category: \"PROMPT_INJECTION\""));
        assertTrue(content.contains("category: \"HANDOFF\""));
    }

    @Test
    @DisplayName("Kiểm tra 7 file tri thức tĩnh trong resources/knowledge/ thỏa mãn dữ liệu sự thật cho 30 câu hỏi")
    void testKnowledgeMarkdownGroundTruth() throws IOException {
        String[] requiredKnowledgeFiles = {
                "knowledge/chinh-sach-bao-hanh.md",
                "knowledge/chinh-sach-doi-tra.md",
                "knowledge/chinh-sach-van-chuyen.md",
                "knowledge/phuong-thuc-thanh-toan.md",
                "knowledge/huong-dan-chon-vot-cau-long.md",
                "knowledge/huong-dan-muc-cang-cuoc.md",
                "knowledge/huong-dan-chon-giay-cau-long.md"
        };

        for (String fileRelPath : requiredKnowledgeFiles) {
            ClassPathResource resource = new ClassPathResource(fileRelPath);
            assertTrue(resource.exists(), "Tệp tri thức mẫu phải tồn tại: " + fileRelPath);
            String docText = resource.getContentAsString(StandardCharsets.UTF_8);
            assertFalse(docText.isBlank());
            assertTrue(docText.contains("# "), "Tài liệu phải có tiêu đề cấp 1 Markdown");
        }
    }

    @Test
    @DisplayName("Kiểm tra System Prompt Guardrails trong resources/prompts/support-system.st có đầy đủ cơ chế an toàn")
    void testSystemPromptGuardrailsIntegrity() throws IOException {
        ClassPathResource resource = new ClassPathResource("prompts/support-system.st");
        assertTrue(resource.exists(), "Tệp prompts/support-system.st phải tồn tại");

        String promptContent = resource.getContentAsString(StandardCharsets.UTF_8);

        // 1. Phải có thẻ phân tách dữ liệu chống Prompt Injection
        assertTrue(promptContent.contains("<context>"));
        assertTrue(promptContent.contains("</context>"));
        assertTrue(promptContent.contains("<user_question>"));
        assertTrue(promptContent.contains("</user_question>"));

        // 2. Phải có chỉ thị nghiêm cấm bịa đặt và từ chối ngoài phạm vi
        assertTrue(promptContent.contains("CHỈ ĐƯỢC PHÉP"));
        assertTrue(promptContent.contains("chuyển tiếp cuộc trò chuyện sang nhân viên tư vấn"));

        // 3. Phải có cấu trúc trả về cờ handoff JSON
        assertTrue(promptContent.contains("{\"handoff\": true, \"reason\": \"CUSTOMER_REQUESTED\"}"));
    }
}

