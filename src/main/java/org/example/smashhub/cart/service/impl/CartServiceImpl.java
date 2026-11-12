package org.example.smashhub.cart.service.impl;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.example.smashhub.cart.dto.request.AddCartItemRequest;
import org.example.smashhub.cart.dto.request.MergeCartRequest;
import org.example.smashhub.cart.dto.request.UpdateCartItemRequest;
import org.example.smashhub.cart.dto.response.CartItemResponse;
import org.example.smashhub.cart.dto.response.CartMutationResponse;
import org.example.smashhub.cart.dto.response.CartResponse;
import org.example.smashhub.cart.entity.CartItem;
import org.example.smashhub.cart.guest.GuestCartStore;
import org.example.smashhub.cart.guest.GuestCartStore.GuestLine;
import org.example.smashhub.cart.repository.CartItemRepository;
import org.example.smashhub.cart.service.CartService;
import org.example.smashhub.catalog.entity.Product;
import org.example.smashhub.catalog.entity.ProductColor;
import org.example.smashhub.catalog.entity.ProductImage;
import org.example.smashhub.catalog.entity.ProductVariant;
import org.example.smashhub.catalog.repository.ProductImageRepository;
import org.example.smashhub.catalog.repository.ProductVariantRepository;
import org.example.smashhub.common.enums.ImagePriority;
import org.example.smashhub.common.enums.InventoryStatus;
import org.example.smashhub.common.enums.ProductStatus;
import org.example.smashhub.common.enums.VariantStatus;
import org.example.smashhub.exception.AppException;
import org.example.smashhub.exception.ErrorCode;
import org.example.smashhub.user.entity.User;
import org.example.smashhub.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class CartServiceImpl implements CartService {

    private static final int MAX_LINE_QUANTITY = 99;

    CartItemRepository cartItemRepository;
    ProductVariantRepository variantRepository;
    ProductImageRepository productImageRepository;
    UserRepository userRepository;
    GuestCartStore guestCartStore;

    // =========================================================================
    // PUBLIC
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public CartMutationResponse getCart(String username, String cartToken) {
        if (isUser(username)) {
            return userResult(username, loadUserCart(username));
        }
        String token = ensureGuestToken(cartToken);
        return guestResult(token, buildCartFromGuestLines(guestCartStore.get(token)));
    }

    @Override
    @Transactional
    public CartMutationResponse addItem(String username, String cartToken, AddCartItemRequest request) {
        ProductVariant variant = requirePurchasableVariant(request.getVariantId());
        int requested = request.getQuantity();

        if (isUser(username)) {
            User user = requireUser(username);
            Optional<CartItem> existing =
                    cartItemRepository.findByUserIdAndVariantId(user.getId(), variant.getId());
            if (existing.isPresent()) {
                CartItem item = existing.get();
                item.setQuantity(clampQuantity(item.getQuantity() + requested, variant.getStock()));
                cartItemRepository.save(item);
            } else {
                cartItemRepository.save(CartItem.builder()
                        .user(user)
                        .variant(variant)
                        .quantity(clampQuantity(requested, variant.getStock()))
                        .build());
            }
            return userResult(username, loadUserCart(username));
        }

        String token = ensureGuestToken(cartToken);
        Map<Long, Integer> map = toMap(guestCartStore.get(token));
        int current = map.getOrDefault(variant.getId(), 0);
        map.put(variant.getId(), clampQuantity(current + requested, variant.getStock()));
        guestCartStore.saveAll(token, toLines(map));
        return guestResult(token, buildCartFromGuestLines(guestCartStore.get(token)));
    }

    @Override
    @Transactional
    public CartMutationResponse updateItem(String username, String cartToken, Long variantId,
                                           UpdateCartItemRequest request) {
        if (isUser(username)) {
            User user = requireUser(username);
            CartItem item = cartItemRepository.findByUserIdAndVariantId(user.getId(), variantId)
                    .orElseThrow(() -> new AppException(ErrorCode.CART_ITEM_NOT_FOUND));
            ProductVariant variant = item.getVariant();
            ensureVariantStillValid(variant);
            item.setQuantity(clampQuantity(request.getQuantity(), variant.getStock()));
            cartItemRepository.save(item);
            return userResult(username, loadUserCart(username));
        }

        String token = requireGuestToken(cartToken);
        Map<Long, Integer> map = toMap(guestCartStore.get(token));
        if (!map.containsKey(variantId)) {
            throw new AppException(ErrorCode.CART_ITEM_NOT_FOUND);
        }
        ProductVariant variant = requirePurchasableVariant(variantId);
        map.put(variantId, clampQuantity(request.getQuantity(), variant.getStock()));
        guestCartStore.saveAll(token, toLines(map));
        return guestResult(token, buildCartFromGuestLines(guestCartStore.get(token)));
    }

    @Override
    @Transactional
    public CartMutationResponse removeItem(String username, String cartToken, Long variantId) {
        if (isUser(username)) {
            User user = requireUser(username);
            if (cartItemRepository.deleteByUserIdAndVariantId(user.getId(), variantId) == 0) {
                throw new AppException(ErrorCode.CART_ITEM_NOT_FOUND);
            }
            return userResult(username, loadUserCart(username));
        }

        String token = requireGuestToken(cartToken);
        Map<Long, Integer> map = toMap(guestCartStore.get(token));
        if (!map.containsKey(variantId)) {
            throw new AppException(ErrorCode.CART_ITEM_NOT_FOUND);
        }
        guestCartStore.remove(token, variantId);
        return guestResult(token, buildCartFromGuestLines(guestCartStore.get(token)));
    }

    @Override
    @Transactional
    public CartMutationResponse clearCart(String username, String cartToken) {
        if (isUser(username)) {
            User user = requireUser(username);
            cartItemRepository.deleteAllByUserId(user.getId());
            return userResult(username, emptyCart());
        }
        String token = ensureGuestToken(cartToken);
        guestCartStore.clear(token);
        return guestResult(token, emptyCart());
    }

    /**
     * Gộp giỏ khách → user DB rồi xóa Redis.
     * Nguồn guest: Redis (cartToken) nếu có; không thì request.items.
     */
    @Override
    @Transactional
    public CartMutationResponse mergeCart(String username, String cartToken, MergeCartRequest request) {
        if (!isUser(username)) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        User user = requireUser(username);

        List<GuestLine> guestLines = resolveGuestLinesForMerge(cartToken, request);

        if (guestLines.isEmpty()) {
            throw new AppException(ErrorCode.CART_MERGE_EMPTY);
        }

        for (GuestLine line : guestLines) {
            Optional<ProductVariant> variantOpt = variantRepository.findById(line.variantId());
            if (variantOpt.isEmpty()) {
                log.warn("Merge: skip unknown variantId={}", line.variantId());
                continue;
            }
            ProductVariant variant = variantOpt.get();
            if (!isPurchasable(variant) || variant.getStock() == null || variant.getStock() <= 0) {
                log.warn("Merge: skip non-purchasable variantId={}", line.variantId());
                continue;
            }

            Optional<CartItem> existing =
                    cartItemRepository.findByUserIdAndVariantId(user.getId(), variant.getId());
            if (existing.isPresent()) {
                CartItem item = existing.get();
                item.setQuantity(clampQuantity(item.getQuantity() + line.quantity(), variant.getStock()));
                cartItemRepository.save(item);
            } else {
                cartItemRepository.save(CartItem.builder()
                        .user(user)
                        .variant(variant)
                        .quantity(clampQuantity(line.quantity(), variant.getStock()))
                        .build());
            }
        }

        if (cartToken != null && !cartToken.isBlank()) {
            guestCartStore.clear(cartToken);
        }

        return userResult(username, loadUserCart(username));
    }

    // =========================================================================
    // USER / GUEST helpers
    // =========================================================================

    private List<GuestLine> resolveGuestLinesForMerge(String cartToken, MergeCartRequest request) {
        if (cartToken != null && !cartToken.isBlank()) {
            List<GuestLine> fromRedis = guestCartStore.get(cartToken);
            if (!fromRedis.isEmpty()) {
                return fromRedis;
            }
        }
        if (request != null && request.getItems() != null && !request.getItems().isEmpty()) {
            return GuestCartStore.fromRequests(request.getItems());
        }
        return List.of();
    }

    private boolean isUser(String username) {
        return username != null && !username.isBlank()
                && !"anonymousUser".equals(username);
    }

    private CartResponse loadUserCart(String username) {
        User user = requireUser(username);
        return toCartResponseFromEntities(cartItemRepository.findByUserIdWithDetails(user.getId()));
    }

    private String ensureGuestToken(String cartToken) {
        if (cartToken != null && !cartToken.isBlank()) {
            return cartToken.trim();
        }
        return guestCartStore.newToken();
    }

    private String requireGuestToken(String cartToken) {
        if (cartToken == null || cartToken.isBlank()) {
            throw new AppException(ErrorCode.CART_TOKEN_REQUIRED);
        }
        return cartToken.trim();
    }

    private CartMutationResponse userResult(String username, CartResponse cart) {
        return CartMutationResponse.builder()
                .cart(cart)
                .cartToken(null)
                .build();
    }

    private CartMutationResponse guestResult(String token, CartResponse cart) {
        return CartMutationResponse.builder()
                .cart(cart)
                .cartToken(token)
                .build();
    }

    private CartResponse buildCartFromGuestLines(List<GuestLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return emptyCart();
        }
        List<CartItemResponse> items = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        int totalQuantity = 0;
        int issueCount = 0;

        for (GuestLine line : lines) {
            Optional<ProductVariant> opt = variantRepository.findById(line.variantId());
            if (opt.isEmpty()) {
                issueCount++;
                continue;
            }
            ProductVariant variant = opt.get();
            // cần color + product; trong TX Hibernate load lazy OK
            CartItemResponse row = toCartItemResponseFromVariant(variant, line.quantity(), null);
            items.add(row);
            totalQuantity += row.getQuantity() == null ? 0 : row.getQuantity();
            if (row.getLineTotal() != null && !row.isUnavailable()) {
                subtotal = subtotal.add(row.getLineTotal());
            }
            if (row.isUnavailable() || row.isStockInsufficient()) {
                issueCount++;
            }
        }

        return CartResponse.builder()
                .items(items)
                .totalItems(items.size())
                .totalQuantity(totalQuantity)
                .subtotal(subtotal)
                .issueCount(issueCount)
                .build();
    }

    // =========================================================================
    // Domain validation
    // =========================================================================

    private User requireUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));
    }

    private ProductVariant requirePurchasableVariant(Long variantId) {
        ProductVariant variant = variantRepository.findById(variantId)
                .orElseThrow(() -> new AppException(ErrorCode.PRODUCT_VARIANT_NOT_FOUND));
        if (!isPurchasable(variant)) {
            throw new AppException(ErrorCode.PRODUCT_VARIANT_INACTIVE);
        }
        if (variant.getStock() == null || variant.getStock() <= 0
                || variant.getInventoryStatus() == InventoryStatus.OUT_OF_STOCK) {
            throw new AppException(ErrorCode.PRODUCT_VARIANT_INSUFFICIENT_STOCK);
        }
        return variant;
    }

    private void ensureVariantStillValid(ProductVariant variant) {
        if (variant.getActive() != VariantStatus.ACTIVE) {
            throw new AppException(ErrorCode.PRODUCT_VARIANT_INACTIVE);
        }
        if (variant.getColor().getProduct().getProductStatus() != ProductStatus.ACTIVE) {
            throw new AppException(ErrorCode.PRODUCT_VARIANT_INACTIVE);
        }
    }

    private boolean isPurchasable(ProductVariant variant) {
        if (variant.getActive() != VariantStatus.ACTIVE) {
            return false;
        }
        ProductColor color = variant.getColor();
        if (color == null || color.getProduct() == null) {
            return false;
        }
        return color.getProduct().getProductStatus() == ProductStatus.ACTIVE;
    }

    private int clampQuantity(int quantity, Integer stock) {
        if (quantity < 1) {
            throw new AppException(ErrorCode.PRODUCT_VARIANT_QUANTITY_INVALID);
        }
        int maxByStock = stock == null ? 0 : Math.max(stock, 0);
        if (maxByStock <= 0) {
            throw new AppException(ErrorCode.PRODUCT_VARIANT_INSUFFICIENT_STOCK);
        }
        int clamped = Math.min(quantity, Math.min(maxByStock, MAX_LINE_QUANTITY));
        if (clamped < 1) {
            throw new AppException(ErrorCode.PRODUCT_VARIANT_INSUFFICIENT_STOCK);
        }
        return clamped;
    }

    // =========================================================================
    // Mapping
    // =========================================================================

    private CartResponse emptyCart() {
        return CartResponse.builder()
                .items(List.of())
                .totalItems(0)
                .totalQuantity(0)
                .subtotal(BigDecimal.ZERO)
                .issueCount(0)
                .build();
    }

    private CartResponse toCartResponseFromEntities(List<CartItem> items) {
        if (items == null || items.isEmpty()) {
            return emptyCart();
        }
        List<CartItemResponse> lines = new ArrayList<>(items.size());
        BigDecimal subtotal = BigDecimal.ZERO;
        int totalQuantity = 0;
        int issueCount = 0;
        for (CartItem item : items) {
            CartItemResponse line = toCartItemResponseFromVariant(
                    item.getVariant(), item.getQuantity(), item.getId());
            lines.add(line);
            totalQuantity += line.getQuantity() == null ? 0 : line.getQuantity();
            if (line.getLineTotal() != null && !line.isUnavailable()) {
                subtotal = subtotal.add(line.getLineTotal());
            }
            if (line.isUnavailable() || line.isStockInsufficient()) {
                issueCount++;
            }
        }
        return CartResponse.builder()
                .items(lines)
                .totalItems(lines.size())
                .totalQuantity(totalQuantity)
                .subtotal(subtotal)
                .issueCount(issueCount)
                .build();
    }

    private CartItemResponse toCartItemResponseFromVariant(ProductVariant variant, int quantity, Long cartItemId) {
        ProductColor color = variant.getColor();
        Product product = color.getProduct();

        boolean unavailable = variant.getActive() != VariantStatus.ACTIVE
                || product.getProductStatus() != ProductStatus.ACTIVE;
        int stock = variant.getStock() == null ? 0 : variant.getStock();
        boolean stockInsufficient = !unavailable && quantity > stock;

        BigDecimal price = product.getPrice();
        BigDecimal salePrice = product.getSalePrice();
        BigDecimal effective = org.example.smashhub.catalog.util.PriceCalculator.effectivePrice(price, salePrice);
        BigDecimal lineTotal = effective.multiply(BigDecimal.valueOf(quantity));

        return CartItemResponse.builder()
                .id(cartItemId)
                .variantId(variant.getId())
                .sku(variant.getSku())
                .size(variant.getSize())
                .quantity(quantity)
                .availableStock(stock)
                .variantStatus(variant.getActive())
                .inventoryStatus(variant.getInventoryStatus())
                .productId(product.getId())
                .productName(product.getName())
                .productSlug(product.getSlug())
                .productStatus(product.getProductStatus())
                .unitPrice(price)
                .salePrice(salePrice)
                .effectivePrice(effective)
                .lineTotal(lineTotal)
                .colorId(color.getId())
                .colorName(color.getColorName())
                .hexCode(color.getHexCode())
                .thumbnailUrl(resolveThumbnail(color.getId()))
                .stockInsufficient(stockInsufficient)
                .unavailable(unavailable)
                .build();
    }

    private String resolveThumbnail(Long colorId) {
        if (colorId == null) {
            return null;
        }
        try {
            List<ProductImage> images = productImageRepository.findByColorIdOrderByOrderIndexAsc(colorId);
            if (images == null || images.isEmpty()) {
                return null;
            }
            return images.stream()
                    .filter(img -> img.getIsMain() == ImagePriority.MAIN)
                    .map(ProductImage::getUrl)
                    .findFirst()
                    .orElseGet(() -> images.stream()
                            .min(Comparator.comparing(
                                    img -> img.getOrderIndex() == null ? Integer.MAX_VALUE : img.getOrderIndex()))
                            .map(ProductImage::getUrl)
                            .orElse(null));
        } catch (Exception e) {
            log.debug("Thumbnail resolve failed colorId={}: {}", colorId, e.toString());
            return null;
        }
    }

    private static Map<Long, Integer> toMap(List<GuestLine> lines) {
        Map<Long, Integer> map = new HashMap<>();
        if (lines != null) {
            for (GuestLine line : lines) {
                map.put(line.variantId(), line.quantity());
            }
        }
        return map;
    }

    private static List<GuestLine> toLines(Map<Long, Integer> map) {
        List<GuestLine> lines = new ArrayList<>(map.size());
        map.forEach((id, qty) -> lines.add(new GuestLine(id, qty)));
        return lines;
    }
}
