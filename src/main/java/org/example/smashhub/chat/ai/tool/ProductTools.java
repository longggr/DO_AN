package org.example.smashhub.chat.ai.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.example.smashhub.catalog.entity.Product;
import org.example.smashhub.catalog.entity.ProductAttribute;
import org.example.smashhub.catalog.entity.ProductImage;
import org.example.smashhub.catalog.entity.ProductVariant;
import org.example.smashhub.catalog.repository.ProductAttributeRepository;
import org.example.smashhub.catalog.repository.ProductImageRepository;
import org.example.smashhub.catalog.repository.ProductRepository;
import org.example.smashhub.catalog.repository.ProductVariantRepository;
import org.example.smashhub.chat.ai.dto.tool.ProductDetailDto;
import org.example.smashhub.chat.ai.dto.tool.ProductSearchResultDto;
import org.example.smashhub.chat.ai.dto.tool.ProductStockDto;
import org.example.smashhub.chat.ai.dto.tool.VariantStockDto;
import org.example.smashhub.chat.ai.vectorstore.Document;
import org.example.smashhub.chat.ai.vectorstore.SearchRequest;
import org.example.smashhub.chat.ai.vectorstore.VectorStore;
import org.example.smashhub.common.enums.ImagePriority;
import org.example.smashhub.common.enums.ProductStatus;
import org.example.smashhub.common.enums.VariantStatus;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * ProductTools: Cung cấp các công cụ CHỈ ĐỌC (Read-Only) để AI tra cứu dữ liệu sản phẩm LIVE từ cơ sở dữ liệu.
 * Mọi kết quả trả về dưới dạng DTO gọn gàng, độc lập, không trả thực thể JPA.
 */
@Component
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Transactional(readOnly = true)
public class ProductTools {

    static final String SEARCH_CACHE_PREFIX = "smashhub:ai:cache:search:";

    ProductRepository productRepository;
    ProductAttributeRepository productAttributeRepository;
    ProductVariantRepository productVariantRepository;
    ProductImageRepository productImageRepository;
    VectorStore vectorStore;
    StringRedisTemplate redisTemplate;
    ObjectMapper objectMapper;

    /**
     * Tool 1: Tìm kiếm sản phẩm thông minh kết hợp Vector Similarity Search + Lọc Live Database (Giá, Tồn kho thực tế).
     *
     * @param query Từ khóa / nhu cầu tìm kiếm (ví dụ: "vợt nhẹ đầu nặng cho đánh đôi")
     * @param category Danh mục mong muốn (tuỳ chọn, ví dụ: "Vợt cầu lông", "Giày cầu lông")
     * @param brand Thương hiệu mong muốn (tuỳ chọn, ví dụ: "Yonex", "Lining", "Victor")
     * @param minPrice Mức giá tối thiểu (tuỳ chọn)
     * @param maxPrice Mức giá tối đa (tuỳ chọn)
     * @param limit Số lượng tối đa trả về (mặc định 5, tối đa 5)
     */
    public List<ProductSearchResultDto> searchProducts(
            String query, String category, String brand, BigDecimal minPrice, BigDecimal maxPrice, Integer limit) {

        int maxResults = (limit != null && limit > 0) ? Math.min(limit, 5) : 5;
        String normalizedQuery = (query != null) ? query.trim() : "";

        // 1. Kiểm tra Cache Redis (TTL 5 phút)
        String cacheKey = buildSearchCacheKey(normalizedQuery, category, brand, minPrice, maxPrice, maxResults);
        try {
            String cachedJson = redisTemplate.opsForValue().get(cacheKey);
            if (cachedJson != null && !cachedJson.isBlank()) {
                log.debug("searchProducts cache hit for key={}", cacheKey);
                return objectMapper.readValue(cachedJson, new TypeReference<List<ProductSearchResultDto>>() {});
            }
        } catch (Exception e) {
            log.warn("Redis error reading searchProducts cache", e);
        }

        // 2. Vector Similarity Search
        Set<Long> candidateProductIds = new LinkedHashSet<>();
        if (!normalizedQuery.isBlank()) {
            SearchRequest searchReq = SearchRequest.query(normalizedQuery)
                    .withTopK(10)
                    .withSimilarityThreshold(0.40);

            List<Document> matchedDocs = vectorStore.similaritySearch(searchReq);
            for (Document doc : matchedDocs) {
                if (doc.getMetadata() != null && "PRODUCT".equals(doc.getMetadata().get("entityType"))) {
                    Object pidObj = doc.getMetadata().get("productId");
                    if (pidObj instanceof Number num) {
                        candidateProductIds.add(num.longValue());
                    } else if (pidObj instanceof String str) {
                        try { candidateProductIds.add(Long.parseLong(str)); } catch (NumberFormatException ignored) {}
                    }
                }
            }
        }

        // Nếu vector search trả về ít kết quả, bổ sung thêm từ tìm kiếm keyword DB và trích xuất tầm giá
        if (candidateProductIds.size() < maxResults) {
            BigDecimal queryPrice = extractPriceFromQuery(normalizedQuery);
            List<Product> allProducts = productRepository.findAll();
            for (Product p : allProducts) {
                if (p.getProductStatus() == ProductStatus.ACTIVE) {
                    if (matchesKeyword(p, normalizedQuery)) {
                        candidateProductIds.add(p.getId());
                    } else if (queryPrice != null) {
                        BigDecimal eff = p.getSalePrice() != null ? p.getSalePrice() : p.getPrice();
                        if (eff != null) {
                            BigDecimal lowerBound = queryPrice.multiply(BigDecimal.valueOf(0.65));
                            BigDecimal upperBound = queryPrice.multiply(BigDecimal.valueOf(1.25));
                            if (eff.compareTo(lowerBound) >= 0 && eff.compareTo(upperBound) <= 0) {
                                candidateProductIds.add(p.getId());
                            }
                        }
                    }
                }
            }
        }

        // 3. Lọc LIVE từ Database: trạng thái ACTIVE, khoảng giá, tồn kho thực tế > 0
        List<ProductSearchResultDto> results = new ArrayList<>();
        for (Long pid : candidateProductIds) {
            if (results.size() >= maxResults) break;

            Optional<Product> optProduct = productRepository.findById(pid);
            if (optProduct.isEmpty()) continue;
            Product p = optProduct.get();

            // Chỉ lấy sản phẩm đang hoạt động
            if (p.getProductStatus() != ProductStatus.ACTIVE) continue;

            // Lọc theo Brand nếu có
            if (brand != null && !brand.isBlank()) {
                if (p.getBrand() == null || !p.getBrand().getName().toLowerCase().contains(brand.toLowerCase().trim())) {
                    continue;
                }
            }

            // Lọc theo Category nếu có
            if (category != null && !category.isBlank()) {
                if (p.getCategory() == null || !p.getCategory().getName().toLowerCase().contains(category.toLowerCase().trim())) {
                    continue;
                }
            }

            // Lọc theo Giá thực tế (Ưu tiên salePrice nếu có)
            BigDecimal effectivePrice = (p.getSalePrice() != null) ? p.getSalePrice() : p.getPrice();
            if (minPrice != null && effectivePrice.compareTo(minPrice) < 0) continue;
            if (maxPrice != null && effectivePrice.compareTo(maxPrice) > 0) continue;

            // Kiểm tra tồn kho LIVE thực tế từ các biến thể (variants)
            List<ProductVariant> variants = productVariantRepository.findByColorProductId(p.getId());
            int liveStock = variants.stream()
                    .filter(v -> v.getActive() == VariantStatus.ACTIVE)
                    .mapToInt(v -> v.getStock() != null ? v.getStock() : 0)
                    .sum();

            // BẮT BUỘC: Không giới thiệu sản phẩm hết hàng!
            if (liveStock <= 0) {
                log.debug("Skipping out-of-stock product id={} (name={})", p.getId(), p.getName());
                continue;
            }

            // Lấy thuộc tính kỹ thuật chính
            Map<String, String> keyAttrs = new HashMap<>();
            List<ProductAttribute> attrs = productAttributeRepository.findByProductId(p.getId());
            for (ProductAttribute a : attrs) {
                keyAttrs.put(a.getAttrName(), a.getAttrValue());
            }

            String thumbnail = resolveProductThumbnail(p.getId());

            ProductSearchResultDto dto = ProductSearchResultDto.builder()
                    .id(p.getId())
                    .name(p.getName())
                    .slug(p.getSlug())
                    .brand(p.getBrand() != null ? p.getBrand().getName() : "")
                    .category(p.getCategory() != null ? p.getCategory().getName() : "")
                    .price(p.getPrice())
                    .salePrice(p.getSalePrice())
                    .stock(liveStock)
                    .inStock(true)
                    .thumbnailUrl(thumbnail)
                    .keyAttributes(keyAttrs)
                    .build();

            results.add(dto);
        }

        // 4. Lưu cache Redis TTL 5 phút
        try {
            redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(results), Duration.ofMinutes(5));
        } catch (Exception e) {
            log.warn("Redis error caching searchProducts results", e);
        }

        return results;
    }

    /**
     * Tool 2: Lấy thông tin chi tiết đầy đủ của sản phẩm theo ID.
     */
    public ProductDetailDto getProductDetail(Long productId) {
        if (productId == null) return null;

        Optional<Product> opt = productRepository.findById(productId);
        if (opt.isEmpty() || opt.get().getProductStatus() != ProductStatus.ACTIVE) {
            return null;
        }

        Product p = opt.get();
        List<ProductVariant> variants = productVariantRepository.findByColorProductId(productId);
        int liveStock = variants.stream()
                .filter(v -> v.getActive() == VariantStatus.ACTIVE)
                .mapToInt(v -> v.getStock() != null ? v.getStock() : 0)
                .sum();

        Map<String, String> attrMap = new HashMap<>();
        List<ProductAttribute> attrs = productAttributeRepository.findByProductId(productId);
        for (ProductAttribute a : attrs) {
            attrMap.put(a.getAttrName(), a.getAttrValue());
        }

        return ProductDetailDto.builder()
                .id(p.getId())
                .name(p.getName())
                .slug(p.getSlug())
                .brand(p.getBrand() != null ? p.getBrand().getName() : "")
                .category(p.getCategory() != null ? p.getCategory().getName() : "")
                .price(p.getPrice())
                .salePrice(p.getSalePrice())
                .stock(liveStock)
                .inStock(liveStock > 0)
                .thumbnailUrl(resolveProductThumbnail(p.getId()))
                .attributes(attrMap)
                .description(p.getDescription())
                .build();
    }

    /**
     * Tool 3: Kiểm tra tồn kho LIVE của sản phẩm theo từng biến thể (size, màu sắc).
     */
    public ProductStockDto checkStock(Long productId) {
        if (productId == null) return null;

        Optional<Product> opt = productRepository.findById(productId);
        if (opt.isEmpty()) return null;
        Product p = opt.get();

        List<ProductVariant> variants = productVariantRepository.findByColorProductId(productId);
        int totalStock = 0;
        List<VariantStockDto> variantDtos = new ArrayList<>();

        for (ProductVariant v : variants) {
            int st = (v.getStock() != null) ? v.getStock() : 0;
            if (v.getActive() == VariantStatus.ACTIVE) {
                totalStock += st;
            }
            String colorName = (v.getColor() != null) ? v.getColor().getColorName() : "";
            variantDtos.add(VariantStockDto.builder()
                    .sku(v.getSku())
                    .size(v.getSize())
                    .colorName(colorName)
                    .stock(st)
                    .available(v.getActive() == VariantStatus.ACTIVE && st > 0)
                    .build());
        }

        return ProductStockDto.builder()
                .productId(productId)
                .productName(p.getName())
                .totalStock(totalStock)
                .inStock(totalStock > 0)
                .variants(variantDtos)
                .build();
    }

    private String resolveProductThumbnail(Long productId) {
        List<ProductImage> images = productImageRepository.findByColorProductIdOrderByOrderIndexAsc(productId);
        if (images.isEmpty()) return null;
        return images.stream()
                .filter(i -> i.getIsMain() == ImagePriority.MAIN)
                .findFirst()
                .or(() -> images.stream().findFirst())
                .map(ProductImage::getUrl)
                .orElse(null);
    }

    private boolean matchesKeyword(Product p, String query) {
        if (query == null || query.isBlank()) return true;
        String lower = query.toLowerCase();
        if (p.getName() != null && p.getName().toLowerCase().contains(lower)) return true;
        try {
            if (p.getBrand() != null && p.getBrand().getName() != null && p.getBrand().getName().toLowerCase().contains(lower)) return true;
        } catch (Exception ignored) {}
        try {
            if (p.getCategory() != null && p.getCategory().getName() != null && p.getCategory().getName().toLowerCase().contains(lower)) return true;
        } catch (Exception ignored) {}
        if (p.getDescription() != null && p.getDescription().toLowerCase().contains(lower)) return true;
        return false;
    }

    private String buildSearchCacheKey(String query, String category, String brand, BigDecimal min, BigDecimal max, int limit) {
        String raw = String.format("%s|%s|%s|%s|%s|%d",
                query,
                category != null ? category : "",
                brand != null ? brand : "",
                min != null ? min.toString() : "",
                max != null ? max.toString() : "",
                limit);
        return SEARCH_CACHE_PREFIX + md5(raw);
    }

    private BigDecimal extractPriceFromQuery(String query) {
        if (query == null || query.isBlank()) return null;
        String lower = query.toLowerCase().trim();
        Pattern p = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(?:triệu|trieu|tr|m)\\b");
        Matcher m = p.matcher(lower);
        if (m.find()) {
            try {
                double val = Double.parseDouble(m.group(1).replace(",", "."));
                return BigDecimal.valueOf((long) (val * 1_000_000));
            } catch (Exception ignored) {}
        }
        Pattern p2 = Pattern.compile("(\\d{1,3}(?:[.,]\\d{3})+)\\s*(?:đ|vnd|dong)?");
        Matcher m2 = p2.matcher(lower);
        if (m2.find()) {
            try {
                String clean = m2.group(1).replace(".", "").replace(",", "");
                return new BigDecimal(clean);
            } catch (Exception ignored) {}
        }
        return null;
    }

    private String md5(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(text.hashCode());
        }
    }
}

