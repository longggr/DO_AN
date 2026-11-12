package org.example.smashhub.shipping.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldDefaults;
import org.example.smashhub.common.persistence.BaseEntity;
import org.example.smashhub.order.entity.Order;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * [MỚI] Bảng lưu vết thông tin vận đơn được tạo qua dịch vụ vận chuyển (GHN).
 * Quan hệ 1-1 với Order.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(callSuper = true, exclude = {"order"})
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "shipments")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class Shipment extends BaseEntity {

    @JsonIgnore
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    Order order;

    @Column(name = "order_code", length = 50, unique = true)
    String orderCode; // Mã vận đơn GHN trả về (vd: GY45SD)

    @Column(name = "client_order_code", length = 100, nullable = false)
    String clientOrderCode; // Mã đơn hàng hệ thống gửi lên GHN đối soát (vd: ORDER-1001)

    @Column(name = "service_id")
    Integer serviceId;

    @Column(name = "service_type_id")
    Integer serviceTypeId;

    @Column(name = "fee", precision = 15, scale = 2)
    BigDecimal fee; // Cước phí thực tế GHN báo

    @Column(name = "cod_amount", precision = 15, scale = 2)
    BigDecimal codAmount; // Tiền thu hộ: COD = order.total, VNPAY = 0

    @Column(name = "expected_delivery_date")
    LocalDateTime expectedDeliveryDate;

    @Column(name = "status", length = 50)
    String status; // Trạng thái GHN: ready_to_pick, picked, delivering, delivered, cancel...

    @Column(name = "status_name", length = 100)
    String statusName;

    @Column(name = "failure_reason")
    String failureReason;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "raw_response", columnDefinition = "LONGTEXT")
    String rawResponse;
}

