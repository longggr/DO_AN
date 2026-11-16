package org.example.smashhub.chat.ai.rag;

import lombok.extern.slf4j.Slf4j;
import org.example.smashhub.catalog.entity.Product;
import org.example.smashhub.catalog.entity.ProductAttribute;
import org.example.smashhub.chat.ai.vectorstore.Document;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Xây dựng đoạn văn bản tiếng Việt tự nhiên và trôi chảy cho sản phẩm để sinh vector embedding.
 * NGUYÊN TẮC QUAN TRỌNG:
 * - Vector chỉ chứa nội dung ÍT ĐỔI: tên, thương hiệu, danh mục, thông số kỹ thuật, mô tả.
 * - GIÁ, TỒN KHO, TRẠNG THÁI BÁN TUYỆT ĐỐI KHÔNG ĐƯỢC ĐƯA VÀO VECTOR; luôn lấy LIVE qua tool/repository.
 */
@Component
@Slf4j
public class ProductDocumentBuilder {

    public Document buildDocument(Product product, List<ProductAttribute> attributes) {
        if (product == null) {
            return null;
        }

        String brandName = product.getBrand() != null ? product.getBrand().getName() : "";
        String categoryName = product.getCategory() != null ? product.getCategory().getName() : "";
        String typeName = product.getType() != null ? product.getType().name() : "";

        StringBuilder sb = new StringBuilder();
        sb.append("Sản phẩm: ").append(product.getName()).append(".\n");
        if (!brandName.isBlank()) {
            sb.append("Thương hiệu: ").append(brandName).append(".\n");
        }
        if (!categoryName.isBlank()) {
            sb.append("Danh mục: ").append(categoryName).append(".\n");
        }
        if (!typeName.isBlank()) {
            sb.append("Phân loại: ").append(typeName).append(".\n");
        }

        // Gộp các thuộc tính kỹ thuật (EAV)
        if (attributes != null && !attributes.isEmpty()) {
            sb.append("Thông số kỹ thuật: ");
            for (int i = 0; i < attributes.size(); i++) {
                ProductAttribute attr = attributes.get(i);
                String friendlyName = mapFriendlyAttributeName(attr.getAttrName());
                sb.append(friendlyName).append(": ").append(attr.getAttrValue());
                if (i < attributes.size() - 1) {
                    sb.append("; ");
                } else {
                    sb.append(".\n");
                }
            }
        }

        // Mô tả sản phẩm
        if (product.getDescription() != null && !product.getDescription().isBlank()) {
            sb.append("Mô tả chi tiết: ").append(product.getDescription().trim());
        }

        String contentText = sb.toString().trim();
        String hash = computeSha256(contentText);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("productId", product.getId());
        metadata.put("title", product.getName());
        metadata.put("brand", brandName);
        metadata.put("category", categoryName);
        metadata.put("entityType", "PRODUCT");
        metadata.put("contentHash", hash);
        metadata.put("active", true);

        return Document.builder()
                .id("prod_" + product.getId())
                .content(contentText)
                .metadata(metadata)
                .build();
    }

    private String mapFriendlyAttributeName(String rawName) {
        if (rawName == null) return "";
        String lower = rawName.toLowerCase().trim();
        return switch (lower) {
            case "weight", "trong_luong" -> "Trọng lượng";
            case "balance_point", "diem_can_bang" -> "Điểm cân bằng";
            case "stiffness", "flexibility", "do_cung" -> "Độ cứng thân";
            case "max_tension", "muc_cang_toi_da" -> "Mức căng tối đa";
            case "play_style", "loi_choi" -> "Lối chơi phù hợp";
            case "target_level", "trinh_do" -> "Trình độ phù hợp";
            case "technology", "cong_nghe" -> "Công nghệ nổi bật";
            case "material", "chat_lieu" -> "Chất liệu khung";
            case "shoe_form", "form_giay" -> "Form bàn chân";
            case "size" -> "Kích cỡ";
            case "length", "chieu_dai" -> "Chiều dài vợt";
            default -> rawName;
        };
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

