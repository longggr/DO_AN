package org.example.smashhub.payment.util;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * [MỚI] Tiện ích mã hóa, ký HMAC-SHA512 và chuẩn hóa dữ liệu cho cổng thanh toán VNPay.
 */
@Slf4j
public final class VnpayUtils {

    private VnpayUtils() {}

    /**
     * Ký HMAC-SHA512 theo đúng quy chuẩn kỹ thuật của VNPay.
     *
     * @param key  Chuỗi bí mật (hash-secret)
     * @param data Dữ liệu cần ký
     * @return Chuỗi Hex chữ hoa / thường (VNPay chấp nhận cả hai, chuẩn hóa trả về chữ thường)
     */
    public static String hmacSHA512(String key, String data) {
        try {
            if (key == null || data == null) {
                throw new IllegalArgumentException("Key và Data không được null");
            }
            Mac hmac512 = Mac.getInstance("HmacSHA512");
            SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA512");
            hmac512.init(secretKey);
            byte[] result = hmac512.doFinal(data.getBytes(StandardCharsets.UTF_8));

            StringBuilder sb = new StringBuilder(2 * result.length);
            for (byte b : result) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception ex) {
            log.error("Lỗi tạo HMAC-SHA512: ", ex);
            return "";
        }
    }

    /**
     * So sánh chữ ký an toàn chống tấn công timing (constant-time comparison).
     */
    public static boolean verifySignature(String rawData, String secureHash, String secretKey) {
        if (secureHash == null || secretKey == null) {
            return false;
        }
        String calculated = hmacSHA512(secretKey, rawData);
        return MessageDigest.isEqual(
                calculated.toLowerCase().getBytes(StandardCharsets.UTF_8),
                secureHash.toLowerCase().getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * Xây dựng chuỗi dữ liệu ký và chuỗi query string từ map tham số.
     * Các tham số được sắp xếp theo bảng chữ cái tăng dần (A-Z).
     *
     * @param params Map tham số vnp_*
     * @return Chuỗi query string đã được URL-encode
     */
    public static String buildHashData(Map<String, String> params) {
        List<String> fieldNames = new ArrayList<>(params.keySet());
        Collections.sort(fieldNames);

        StringBuilder sb = new StringBuilder();
        Iterator<String> itr = fieldNames.iterator();
        while (itr.hasNext()) {
            String fieldName = itr.next();
            String fieldValue = params.get(fieldName);
            if (fieldValue != null && !fieldValue.isBlank()
                    && !"vnp_SecureHash".equals(fieldName)
                    && !"vnp_SecureHashType".equals(fieldName)) {
                try {
                    sb.append(URLEncoder.encode(fieldName, StandardCharsets.US_ASCII.toString()));
                    sb.append('=');
                    sb.append(URLEncoder.encode(fieldValue, StandardCharsets.US_ASCII.toString()));
                } catch (Exception e) {
                    log.error("URL encode error: ", e);
                }
                if (itr.hasNext()) {
                    sb.append('&');
                }
            }
        }
        // Xóa dấu & thừa ở cuối nếu có
        String result = sb.toString();
        if (result.endsWith("&")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /**
     * Loại bỏ dấu tiếng Việt và ký tự đặc biệt theo yêu cầu của vnp_OrderInfo.
     */
    public static String removeAccents(String text) {
        if (text == null) return "";
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFD);
        Pattern pattern = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
        String noAccents = pattern.matcher(normalized).replaceAll("").replace('đ', 'd').replace('Đ', 'D');
        // Loại bỏ ký tự đặc biệt, chỉ giữ chữ, số và khoảng trắng
        return noAccents.replaceAll("[^a-zA-Z0-9 ]", "").trim();
    }
}

