package org.example.smashhub.chat.ai.rag;

import org.example.smashhub.catalog.entity.Brand;
import org.example.smashhub.catalog.entity.Category;
import org.example.smashhub.catalog.entity.Product;
import org.example.smashhub.catalog.entity.ProductAttribute;
import org.example.smashhub.chat.ai.vectorstore.Document;
import org.example.smashhub.common.enums.ProductStatus;
import org.example.smashhub.common.enums.ProductType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProductDocumentBuilderTest {

    private ProductDocumentBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new ProductDocumentBuilder();
    }

    @Test
    @DisplayName("Tạo Document thành công với câu văn tiếng Việt tự nhiên và đầy đủ thuộc tính kỹ thuật")
    void testBuildDocumentSuccessful() {
        Brand brand = new Brand();
        brand.setName("Yonex");

        Category category = new Category();
        category.setName("Vợt cầu lông");

        Product product = new Product();
        product.setId(101L);
        product.setName("Vợt cầu lông Yonex Astrox 88D Pro");
        product.setBrand(brand);
        product.setCategory(category);
        product.setType(ProductType.RACKET);
        product.setPrice(BigDecimal.valueOf(4200000));
        product.setSalePrice(BigDecimal.valueOf(3990000));
        product.setProductStatus(ProductStatus.ACTIVE);
        product.setDescription("Vợt tấn công uy lực dành cho người chơi cầu sau trong đánh đôi.");

        List<ProductAttribute> attributes = List.of(
                ProductAttribute.builder().attrName("weight").attrValue("4U (83g)").build(),
                ProductAttribute.builder().attrName("balance_point").attrValue("Nặng đầu (305mm)").build(),
                ProductAttribute.builder().attrName("stiffness").attrValue("Cứng").build(),
                ProductAttribute.builder().attrName("max_tension").attrValue("28 lbs").build(),
                ProductAttribute.builder().attrName("play_style").attrValue("Tấn công uy lực cầu sau").build(),
                ProductAttribute.builder().attrName("target_level").attrValue("Khá, chuyên nghiệp").build()
        );

        Document doc = builder.buildDocument(product, attributes);

        assertNotNull(doc);
        assertEquals("prod_101", doc.getId());

        String content = doc.getContent();
        // Kiểm tra cấu trúc câu tiếng Việt tự nhiên
        assertTrue(content.contains("Sản phẩm: Vợt cầu lông Yonex Astrox 88D Pro."));
        assertTrue(content.contains("Thương hiệu: Yonex."));
        assertTrue(content.contains("Danh mục: Vợt cầu lông."));
        assertTrue(content.contains("Trọng lượng: 4U (83g)"));
        assertTrue(content.contains("Điểm cân bằng: Nặng đầu (305mm)"));
        assertTrue(content.contains("Độ cứng thân: Cứng"));
        assertTrue(content.contains("Mức căng tối đa: 28 lbs"));
        assertTrue(content.contains("Lối chơi phù hợp: Tấn công uy lực cầu sau"));
        assertTrue(content.contains("Trình độ phù hợp: Khá, chuyên nghiệp"));
        assertTrue(content.contains("Mô tả chi tiết: Vợt tấn công uy lực dành cho người chơi cầu sau trong đánh đôi."));

        // NGUYÊN TẮC QUAN TRỌNG: Giá và tồn kho tuyệt đối không được đưa vào nội dung vector
        assertFalse(content.contains("4200000"));
        assertFalse(content.contains("3990000"));
        assertFalse(content.contains("4.200.000"));
        assertFalse(content.contains("3.990.000"));

        // Kiểm tra Metadata
        assertEquals(101L, doc.getMetadata().get("productId"));
        assertEquals("Yonex", doc.getMetadata().get("brand"));
        assertEquals("Vợt cầu lông", doc.getMetadata().get("category"));
        assertEquals("PRODUCT", doc.getMetadata().get("entityType"));
        assertNotNull(doc.getMetadata().get("contentHash"));
    }

    @Test
    @DisplayName("Hàm computeSha256 tính toán hash chính xác và nhất quán")
    void testComputeSha256Consistency() {
        String text = "Kiểm tra mã băm SHA-256";
        String hash1 = builder.computeSha256(text);
        String hash2 = builder.computeSha256(text);

        assertNotNull(hash1);
        assertEquals(64, hash1.length());
        assertEquals(hash1, hash2);

        String hashDiff = builder.computeSha256(text + " thay đổi");
        assertNotEquals(hash1, hashDiff);
    }

    @Test
    @DisplayName("Trả về null nếu product đầu vào là null")
    void testBuildDocumentNullProduct() {
        Document doc = builder.buildDocument(null, List.of());
        assertNull(doc);
    }
}
