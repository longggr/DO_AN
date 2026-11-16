package org.example.smashhub.chat.ai.eval;

import org.example.smashhub.chat.ai.service.AiAssistantService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class Phase2ProductAdvisoryEvaluationTest {

    @Test
    @DisplayName("Đánh giá tính toàn vẹn của eval/phase2.yaml: Đủ đúng 40 test case và bao phủ 8 nhóm nghiệp vụ tư vấn")
    void testPhase2EvaluationSuiteIntegrity() throws IOException {
        File evalFile = new File("eval/phase2.yaml");
        assertTrue(evalFile.exists(), "Tệp eval/phase2.yaml phải tồn tại ở thư mục gốc của dự án");

        String content = Files.readString(evalFile.toPath(), StandardCharsets.UTF_8);
        assertNotNull(content);

        // Đếm số lượng kịch bản kiểm thử (id: 1 đến id: 40)
        int testCaseCount = 0;
        for (int i = 1; i <= 40; i++) {
            assertTrue(content.contains("id: " + i), "Thiếu test case id: " + i);
            testCaseCount++;
        }
        assertEquals(40, testCaseCount, "Bộ eval Phase 2 phải có đầy đủ đúng 40 câu hỏi mẫu");

        // Kiểm tra 8 danh mục cốt lõi
        assertTrue(content.contains("category: \"RACKET_PLAY_STYLE\""));
        assertTrue(content.contains("category: \"RACKET_BUDGET\""));
        assertTrue(content.contains("category: \"SHOES_ADVISORY\""));
        assertTrue(content.contains("category: \"STRINGS_AND_ACC\""));
        assertTrue(content.contains("category: \"AMBIGUOUS_QUERY\""));
        assertTrue(content.contains("category: \"OUT_OF_STOCK\""));
        assertTrue(content.contains("category: \"OUT_OF_SCOPE_HANDOFF\""));
        assertTrue(content.contains("category: \"SPECS_AND_COMPARISON\""));
    }

    @Test
    @DisplayName("Đánh giá Guardrails trong eval/phase2.yaml: Kiểm soát câu hỏi mơ hồ và 0% giới thiệu hàng hết tồn kho")
    void testGuardrailsInEvaluationSuite() throws IOException {
        File evalFile = new File("eval/phase2.yaml");
        String content = Files.readString(evalFile.toPath(), StandardCharsets.UTF_8);

        // 1. Kiểm tra nhóm câu hỏi mơ hồ (AMBIGUOUS_QUERY): Phải yêu cầu làm rõ, không vội vã đưa thẻ
        assertTrue(content.contains("category: \"AMBIGUOUS_QUERY\""));
        assertTrue(content.contains("CLARIFY_QUESTION"));

        // 2. Kiểm tra nhóm hết hàng (OUT_OF_STOCK): Tuyệt đối từ chối hoặc báo hết hàng
        assertTrue(content.contains("category: \"OUT_OF_STOCK\""));
        assertTrue(content.contains("HONEST_REFUSAL") || content.contains("STOCK_CHECK_OR_REFUSAL"));

        // 3. Kiểm tra Tool Calling: các tool searchProducts, getProductDetail, checkStock đều có trong bộ eval
        assertTrue(content.contains("target_tool: \"searchProducts\""));
        assertTrue(content.contains("target_tool: \"getProductDetail\""));
        assertTrue(content.contains("target_tool: \"checkStock\""));
    }

    @Test
    @DisplayName("Đánh giá bộ nhận diện ý định tư vấn sản phẩm (isProductAdvisoryIntent) hoạt động chính xác")
    void testProductAdvisoryIntentDetection() throws Exception {
        Method method = AiAssistantService.class.getDeclaredMethod("isProductAdvisoryIntent", String.class);
        method.setAccessible(true);

        AiAssistantService service = new AiAssistantService(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        // Các câu hỏi tư vấn sản phẩm -> phải trả về TRUE
        assertTrue((Boolean) method.invoke(service, "Tư vấn cho mình cây vợt công thủ toàn diện"));
        assertTrue((Boolean) method.invoke(service, "Có mẫu giày cầu lông nào tầm 1 triệu 5 không"));
        assertTrue((Boolean) method.invoke(service, "Cước cầu lông loại nào bền nhất"));
        assertTrue((Boolean) method.invoke(service, "Vợt nhẹ đầu nặng cho đánh đôi"));
        assertTrue((Boolean) method.invoke(service, "Giày form bè 3E cho người chân to"));

        // Các câu hỏi không phải tư vấn sản phẩm -> trả về FALSE
        assertFalse((Boolean) method.invoke(service, "Chính sách đổi trả hàng như thế nào?"));
        assertFalse((Boolean) method.invoke(service, "Shop giao hàng mất mấy ngày?"));
        assertFalse((Boolean) method.invoke(service, "Hôm nay thời tiết đẹp quá nhỉ?"));
    }

    @Test
    @DisplayName("Đánh giá bộ nhận diện ý định chuyển giao nhân viên (isHumanHandoffIntent) hoạt động chính xác")
    void testHumanHandoffIntentDetection() throws Exception {
        Method method = AiAssistantService.class.getDeclaredMethod("isHumanHandoffIntent", String.class);
        method.setAccessible(true);

        AiAssistantService service = new AiAssistantService(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        // Các câu hỏi yêu cầu gặp nhân viên trực tiếp
        assertTrue((Boolean) method.invoke(service, "Cho tôi gặp nhân viên tư vấn"));
        assertTrue((Boolean) method.invoke(service, "Tôi muốn nói chuyện với nhân viên trực tiếp"));
        assertTrue((Boolean) method.invoke(service, "Chuyển máy cho admin đi bạn"));
        assertTrue((Boolean) method.invoke(service, "Giao sai hàng rồi, gặp nhân viên gấp"));

        // Câu hỏi thông thường -> trả về FALSE
        assertFalse((Boolean) method.invoke(service, "Vợt Yonex Astrox 77 giá bao nhiêu?"));
        assertFalse((Boolean) method.invoke(service, "Bảo hành 90 ngày đúng không shop?"));
    }
}

