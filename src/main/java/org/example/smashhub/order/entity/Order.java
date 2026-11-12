package org.example.smashhub.order.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldDefaults;
import org.example.smashhub.common.enums.OrderStatus;
import org.example.smashhub.common.enums.PaymentMethod;
import org.example.smashhub.common.enums.ShippingMethod;
import org.example.smashhub.common.persistence.BaseEntity;
import org.example.smashhub.location.entity.District;
import org.example.smashhub.location.entity.Province;
import org.example.smashhub.location.entity.Ward;
import org.example.smashhub.user.entity.User;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(callSuper = true, exclude = {"user", "items"})
@Entity
@Table(name = "orders")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class Order extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_status", nullable = false, length = 20)
    OrderStatus orderStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "shipping_method", nullable = false, length = 15)
    ShippingMethod shippingMethod;

    @Column(name = "shipping_recipient_name", nullable = false)
    String shippingRecipientName;

    @Column(name = "shipping_phone", nullable = false, length = 20)
    String shippingPhone;

    @Column(name = "shipping_line1", nullable = false)
    String shippingLine1;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shipping_province_id", nullable = false)
    Province shippingProvince;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shipping_district_id", nullable = false)
    District shippingDistrict;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shipping_ward_code", nullable = false)
    Ward shippingWard;

    @Column(name = "subtotal", nullable = false, precision = 15, scale = 2)
    BigDecimal subtotal;

    @Column(name = "shipping_cost", nullable = false, precision = 15, scale = 2)
    BigDecimal shippingCost;

    @Column(name = "discount", nullable = false, precision = 15, scale = 2)
    BigDecimal discount;

    @Column(name = "total", nullable = false, precision = 15, scale = 2)
    BigDecimal total;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    PaymentMethod paymentMethod;

    @Column(name = "note")
    String note; // [MỚI] Ghi chú của khách khi đặt hàng

    @Column(name = "stock_restored", nullable = false)
    @Builder.Default
    boolean stockRestored = false; // [MỚI] Cờ đảm bảo hoàn kho idempotent đúng 1 lần duy nhất

    @JsonIgnore
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    List<OrderItem> items = new ArrayList<>(); // [MỚI] Danh sách các sản phẩm snapshot trong đơn
}
