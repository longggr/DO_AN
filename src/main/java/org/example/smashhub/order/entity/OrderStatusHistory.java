package org.example.smashhub.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
import org.example.smashhub.common.enums.OrderStatus;
import org.example.smashhub.common.persistence.BaseEntity;

/**
 * [MỚI] Bảng nhật ký audit lịch sử biến động trạng thái đơn hàng.
 * Ghi lại ai đổi, lúc nào, từ trạng thái nào sang trạng thái nào.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(callSuper = true, exclude = {"order"})
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "order_status_history")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class OrderStatusHistory extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    Order order;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    OrderStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false)
    OrderStatus toStatus;

    @Column(name = "updated_by", length = 100, nullable = false)
    String updatedBy;

    @Column(name = "note")
    String note;
}

