package org.example.smashhub.payment.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VnpayUtilsTest {

    @Test
    @DisplayName("HMAC-SHA512 phải tạo ra chuỗi hash 128 ký tự hex hợp lệ")
    void testHmacSHA512() {
        String secret = "TEST_SECRET_KEY";
        String data = "vnp_Amount=10000000&vnp_Command=pay&vnp_TmnCode=DEMOVNPAY";

        String hash = VnpayUtils.hmacSHA512(secret, data);

        assertNotNull(hash);
        assertEquals(128, hash.length());
        // Hash cho cùng input phải luôn ra cùng output (deterministic)
        assertEquals(hash, VnpayUtils.hmacSHA512(secret, data));
    }

    @Test
    @DisplayName("Xác thực chữ ký verifySignature phải trả về true khi chữ ký khớp và false khi sai")
    void testVerifySignature() {
        String secret = "MY_HASH_SECRET";
        String rawData = "vnp_Amount=500000&vnp_TxnRef=123456";
        String hash = VnpayUtils.hmacSHA512(secret, rawData);

        assertTrue(VnpayUtils.verifySignature(rawData, hash, secret));
        assertTrue(VnpayUtils.verifySignature(rawData, hash.toUpperCase(), secret), "Phải không phân biệt hoa thường");
        assertFalse(VnpayUtils.verifySignature(rawData, hash + "tampered", secret));
        assertFalse(VnpayUtils.verifySignature(rawData, hash, "WRONG_SECRET"));
    }

    @Test
    @DisplayName("buildHashData phải sắp xếp các key theo thứ tự A-Z và bỏ qua vnp_SecureHash")
    void testBuildHashData() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("vnp_TxnRef", "ORDER_100");
        params.put("vnp_Amount", "200000");
        params.put("vnp_SecureHash", "should_be_ignored");
        params.put("vnp_Command", "pay");

        String hashData = VnpayUtils.buildHashData(params);

        assertEquals("vnp_Amount=200000&vnp_Command=pay&vnp_TxnRef=ORDER_100", hashData);
    }

    @Test
    @DisplayName("removeAccents phải loại bỏ dấu tiếng Việt và ký tự đặc biệt cho vnp_OrderInfo")
    void testRemoveAccents() {
        String text = "Thanh toán đơn hàng #1234 - Vợt cầu lông Yonex!";
        String result = VnpayUtils.removeAccents(text);

        assertEquals("Thanh toan don hang 1234  Vot cau long Yonex", result);
        assertFalse(result.contains("đ"));
        assertFalse(result.contains("á"));
        assertFalse(result.contains("#"));
        assertFalse(result.contains("!"));
    }
}

