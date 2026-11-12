package org.example.smashhub.catalog.util;

import java.math.BigDecimal;

/**
 * [MỚI] Tiện ích tính toán giá bán thống nhất trong toàn hệ thống.
 * Đảm bảo logic tính effective price tập trung một nơi duy nhất.
 */
public final class PriceCalculator {

    private PriceCalculator() {}

    /**
     * Nơi duy nhất tính giá bán thực tế của sản phẩm (effective price).
     * Nếu có salePrice > 0 và salePrice < regularPrice thì lấy salePrice; ngược lại lấy regularPrice.
     *
     * @param price     Giá gốc
     * @param salePrice Giá khuyến mãi
     * @return Giá bán có hiệu lực
     */
    public static BigDecimal effectivePrice(BigDecimal price, BigDecimal salePrice) {
        if (salePrice != null && salePrice.compareTo(BigDecimal.ZERO) > 0
                && (price == null || salePrice.compareTo(price) < 0)) {
            return salePrice;
        }
        return price != null ? price : BigDecimal.ZERO;
    }
}

