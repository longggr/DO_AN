package org.example.smashhub.chat.ai.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.smashhub.catalog.entity.*;
import org.example.smashhub.catalog.repository.ProductAttributeRepository;
import org.example.smashhub.catalog.repository.ProductImageRepository;
import org.example.smashhub.catalog.repository.ProductRepository;
import org.example.smashhub.catalog.repository.ProductVariantRepository;
import org.example.smashhub.chat.ai.dto.tool.ProductDetailDto;
import org.example.smashhub.chat.ai.dto.tool.ProductSearchResultDto;
import org.example.smashhub.chat.ai.dto.tool.ProductStockDto;
import org.example.smashhub.chat.ai.vectorstore.Document;
import org.example.smashhub.chat.ai.vectorstore.SearchRequest;
import org.example.smashhub.chat.ai.vectorstore.VectorStore;
import org.example.smashhub.common.enums.ImagePriority;
import org.example.smashhub.common.enums.ProductStatus;
import org.example.smashhub.common.enums.ProductType;
import org.example.smashhub.common.enums.VariantStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductToolsTest {

    @Mock
    ProductRepository productRepository;
    @Mock
    ProductAttributeRepository productAttributeRepository;
    @Mock
    ProductVariantRepository productVariantRepository;
    @Mock
    ProductImageRepository productImageRepository;
    @Mock
    VectorStore vectorStore;
    @Mock
    StringRedisTemplate redisTemplate;
    @Mock
    ValueOperations<String, String> valueOperations;

    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    ProductTools productTools;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        productTools = new ProductTools(
                productRepository,
                productAttributeRepository,
                productVariantRepository,
                productImageRepository,
                vectorStore,
                redisTemplate,
                objectMapper
        );
    }

    @Test
    @DisplayName("searchProducts: Lọc LIVE từ DB - Chỉ lấy sản phẩm ACTIVE và TỒN KHO > 0")
    void testSearchProductsVectorAndLiveDbFilter() {
        when(valueOperations.get(anyString())).thenReturn(null);

        // Vector store trả về 3 ứng viên
        Document doc1 = Document.builder().id("prod_1").metadata(Map.of("productId", 1L, "entityType", "PRODUCT")).build();
        Document doc2 = Document.builder().id("prod_2").metadata(Map.of("productId", 2L, "entityType", "PRODUCT")).build();
        Document doc3 = Document.builder().id("prod_3").metadata(Map.of("productId", 3L, "entityType", "PRODUCT")).build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc1, doc2, doc3));

        Brand yonex = new Brand();
        yonex.setName("Yonex");
        Category racketCat = new Category();
        racketCat.setName("Vợt cầu lông");

        // Sản phẩm 1: ACTIVE, còn hàng (stock=5) -> ĐỦ ĐIỀU KIỆN
        Product p1 = new Product();
        p1.setId(1L);
        p1.setName("Yonex Astrox 77 Pro");
        p1.setSlug("yonex-astrox-77-pro");
        p1.setBrand(yonex);
        p1.setCategory(racketCat);
        p1.setPrice(BigDecimal.valueOf(3200000));
        p1.setSalePrice(BigDecimal.valueOf(2990000));
        p1.setProductStatus(ProductStatus.ACTIVE);

        ProductVariant v1 = ProductVariant.builder().sku("AX77P-4U").stock(5).active(VariantStatus.ACTIVE).build();
        when(productRepository.findById(1L)).thenReturn(Optional.of(p1));
        when(productVariantRepository.findByColorProductId(1L)).thenReturn(List.of(v1));

        // Sản phẩm 2: ACTIVE nhưng HẾT HÀNG (stock=0) -> BẮT BUỘC BỊ LOẠI TRỪ
        Product p2 = new Product();
        p2.setId(2L);
        p2.setName("Yonex Arcsaber 11 Pro");
        p2.setSlug("yonex-arcsaber-11-pro");
        p2.setBrand(yonex);
        p2.setCategory(racketCat);
        p2.setPrice(BigDecimal.valueOf(4100000));
        p2.setProductStatus(ProductStatus.ACTIVE);

        ProductVariant v2 = ProductVariant.builder().sku("ARC11P-4U").stock(0).active(VariantStatus.ACTIVE).build();
        when(productRepository.findById(2L)).thenReturn(Optional.of(p2));
        when(productVariantRepository.findByColorProductId(2L)).thenReturn(List.of(v2));

        // Sản phẩm 3: Tồn kho còn nhưng NGỪNG KINH DOANH (DISCONTINUED) -> BẮT BUỘC BỊ LOẠI TRỪ
        Product p3 = new Product();
        p3.setId(3L);
        p3.setName("Yonex Voltric Z-Force II");
        p3.setSlug("yonex-voltric-z-force-ii");
        p3.setBrand(yonex);
        p3.setCategory(racketCat);
        p3.setPrice(BigDecimal.valueOf(3800000));
        p3.setProductStatus(ProductStatus.DISCONTINUED);

        ProductVariant v3 = ProductVariant.builder().sku("VTZF2-4U").stock(10).active(VariantStatus.ACTIVE).build();
        when(productRepository.findById(3L)).thenReturn(Optional.of(p3));
        when(productVariantRepository.findByColorProductId(3L)).thenReturn(List.of(v3));

        List<ProductSearchResultDto> results = productTools.searchProducts(
                "vợt công thủ toàn diện", null, null, null, null, 5);

        assertNotNull(results);
        assertEquals(1, results.size());
        assertEquals(1L, results.get(0).getId());
        assertEquals("Yonex Astrox 77 Pro", results.get(0).getName());
        assertEquals(5, results.get(0).getStock());
        assertTrue(results.get(0).isInStock());
    }

    @Test
    @DisplayName("searchProducts: Lọc chính xác theo khoảng giá minPrice, maxPrice và Thương hiệu")
    void testSearchProductsPriceRangeAndBrandFilter() {
        when(valueOperations.get(anyString())).thenReturn(null);

        Document docA = Document.builder().id("prod_10").metadata(Map.of("productId", 10L, "entityType", "PRODUCT")).build();
        Document docB = Document.builder().id("prod_20").metadata(Map.of("productId", 20L, "entityType", "PRODUCT")).build();
        Document docC = Document.builder().id("prod_30").metadata(Map.of("productId", 30L, "entityType", "PRODUCT")).build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(docA, docB, docC));

        Brand yonex = new Brand();
        yonex.setName("Yonex");
        Brand lining = new Brand();
        lining.setName("Lining");

        // Product A: Yonex, giá 2,500,000 -> NẰM TRONG KHOẢNG [2M, 3M] VÀ ĐÚNG BRAND -> CHỌN
        Product pA = new Product();
        pA.setId(10L);
        pA.setName("Yonex Nanoflare 700");
        pA.setBrand(yonex);
        pA.setPrice(BigDecimal.valueOf(2500000));
        pA.setProductStatus(ProductStatus.ACTIVE);
        when(productRepository.findById(10L)).thenReturn(Optional.of(pA));
        when(productVariantRepository.findByColorProductId(10L)).thenReturn(
                List.of(ProductVariant.builder().stock(3).active(VariantStatus.ACTIVE).build()));

        // Product B: Yonex, giá 1,500,000 -> DƯỚI 2M -> BỊ LOẠI
        Product pB = new Product();
        pB.setId(20L);
        pB.setName("Yonex Play 100");
        pB.setBrand(yonex);
        pB.setPrice(BigDecimal.valueOf(1500000));
        pB.setProductStatus(ProductStatus.ACTIVE);
        when(productRepository.findById(20L)).thenReturn(Optional.of(pB));
        when(productVariantRepository.findByColorProductId(20L)).thenReturn(
                List.of(ProductVariant.builder().stock(8).active(VariantStatus.ACTIVE).build()));

        // Product C: Lining, giá 2,800,000 -> NẰM TRONG KHOẢNG GIÁ NHƯNG SAI BRAND -> BỊ LOẠI
        Product pC = new Product();
        pC.setId(30L);
        pC.setName("Lining Halbertec 8000");
        pC.setBrand(lining);
        pC.setPrice(BigDecimal.valueOf(2800000));
        pC.setProductStatus(ProductStatus.ACTIVE);
        when(productRepository.findById(30L)).thenReturn(Optional.of(pC));
        when(productVariantRepository.findByColorProductId(30L)).thenReturn(
                List.of(ProductVariant.builder().stock(5).active(VariantStatus.ACTIVE).build()));

        List<ProductSearchResultDto> results = productTools.searchProducts(
                "vợt nhẹ tốc độ", null, "Yonex", BigDecimal.valueOf(2000000), BigDecimal.valueOf(3000000), 5);

        assertEquals(1, results.size());
        assertEquals(10L, results.get(0).getId());
        assertEquals("Yonex Nanoflare 700", results.get(0).getName());
    }

    @Test
    @DisplayName("searchProducts: Cache Hit Redis trả về kết quả ngay lập tức không truy vấn DB")
    void testSearchProductsCacheHit() throws Exception {
        ProductSearchResultDto cachedItem = ProductSearchResultDto.builder()
                .id(99L)
                .name("Vợt Yonex Cache Sẵn")
                .price(BigDecimal.valueOf(2000000))
                .stock(10)
                .inStock(true)
                .build();
        String json = objectMapper.writeValueAsString(List.of(cachedItem));

        when(valueOperations.get(anyString())).thenReturn(json);

        List<ProductSearchResultDto> results = productTools.searchProducts(
                "vợt", null, null, null, null, 5);

        assertEquals(1, results.size());
        assertEquals(99L, results.get(0).getId());
        assertEquals("Vợt Yonex Cache Sẵn", results.get(0).getName());

        verifyNoInteractions(vectorStore);
        verifyNoInteractions(productRepository);
    }

    @Test
    @DisplayName("getProductDetail: Trả về thông tin chi tiết đầy đủ, thông số kỹ thuật và ảnh thumbnail")
    void testGetProductDetail() {
        Brand victor = new Brand();
        victor.setName("Victor");
        Category shoesCat = new Category();
        shoesCat.setName("Giày cầu lông");

        Product product = new Product();
        product.setId(50L);
        product.setName("Giày cầu lông Victor P9200III");
        product.setSlug("giay-victor-p9200iii");
        product.setBrand(victor);
        product.setCategory(shoesCat);
        product.setType(ProductType.SHOES);
        product.setPrice(BigDecimal.valueOf(2600000));
        product.setSalePrice(BigDecimal.valueOf(2450000));
        product.setProductStatus(ProductStatus.ACTIVE);
        product.setDescription("Giày cầu lông hỗ trợ giảm chấn đỉnh cao.");

        when(productRepository.findById(50L)).thenReturn(Optional.of(product));

        // Variants
        ProductVariant v1 = ProductVariant.builder().sku("P9200-40").size("40").stock(3).active(VariantStatus.ACTIVE).build();
        ProductVariant v2 = ProductVariant.builder().sku("P9200-41").size("41").stock(2).active(VariantStatus.ACTIVE).build();
        when(productVariantRepository.findByColorProductId(50L)).thenReturn(List.of(v1, v2));

        // Attributes
        ProductAttribute attr1 = ProductAttribute.builder().attrName("shoe_form").attrValue("Form bè 3E").build();
        ProductAttribute attr2 = ProductAttribute.builder().attrName("technology").attrValue("EnergyMax V").build();
        when(productAttributeRepository.findByProductId(50L)).thenReturn(List.of(attr1, attr2));

        // Images
        ProductImage mainImg = ProductImage.builder().url("https://smashhub.vn/images/p9200.jpg").isMain(ImagePriority.MAIN).build();
        when(productImageRepository.findByColorProductIdOrderByOrderIndexAsc(50L)).thenReturn(List.of(mainImg));

        ProductDetailDto detail = productTools.getProductDetail(50L);

        assertNotNull(detail);
        assertEquals(50L, detail.getId());
        assertEquals("Giày cầu lông Victor P9200III", detail.getName());
        assertEquals("Victor", detail.getBrand());
        assertEquals(5, detail.getStock());
        assertTrue(detail.isInStock());
        assertEquals("https://smashhub.vn/images/p9200.jpg", detail.getThumbnailUrl());
        assertEquals("Form bè 3E", detail.getAttributes().get("shoe_form"));
        assertEquals("EnergyMax V", detail.getAttributes().get("technology"));
    }

    @Test
    @DisplayName("checkStock: Kiểm tra tồn kho theo từng biến thể màu sắc và kích cỡ")
    void testCheckStock() {
        Product product = new Product();
        product.setId(70L);
        product.setName("Cước cầu lông Yonex BG65");
        when(productRepository.findById(70L)).thenReturn(Optional.of(product));

        ProductColor white = ProductColor.builder().colorName("Trắng").build();
        ProductColor yellow = ProductColor.builder().colorName("Vàng").build();

        ProductVariant v1 = ProductVariant.builder().sku("BG65-W").color(white).stock(15).active(VariantStatus.ACTIVE).build();
        ProductVariant v2 = ProductVariant.builder().sku("BG65-Y").color(yellow).stock(0).active(VariantStatus.ACTIVE).build();
        ProductVariant v3 = ProductVariant.builder().sku("BG65-BK").stock(5).active(VariantStatus.INACTIVE).build();

        when(productVariantRepository.findByColorProductId(70L)).thenReturn(List.of(v1, v2, v3));

        ProductStockDto stockDto = productTools.checkStock(70L);

        assertNotNull(stockDto);
        assertEquals(70L, stockDto.getProductId());
        assertEquals("Cước cầu lông Yonex BG65", stockDto.getProductName());
        // Tổng tồn kho active chỉ tính v1 (15) + v2 (0) = 15; v3 bị INACTIVE không tính vào available
        assertEquals(15, stockDto.getTotalStock());
        assertTrue(stockDto.isInStock());
        assertEquals(3, stockDto.getVariants().size());
        assertTrue(stockDto.getVariants().get(0).isAvailable());
        assertFalse(stockDto.getVariants().get(1).isAvailable()); // stock = 0
        assertFalse(stockDto.getVariants().get(2).isAvailable()); // INACTIVE
    }
}
