package org.example.smashhub.order.repository;

import jakarta.persistence.LockModeType;
import org.example.smashhub.common.enums.OrderStatus;
import org.example.smashhub.order.entity.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    Page<Order> findByUserIdOrderByCreateDateDesc(Long userId, Pageable pageable);

    @Query("""
            SELECT o FROM Order o
            JOIN FETCH o.user u
            JOIN FETCH o.shippingProvince p
            JOIN FETCH o.shippingDistrict d
            JOIN FETCH o.shippingWard w
            LEFT JOIN FETCH o.items i
            WHERE o.id = :id AND o.user.id = :userId
            """)
    Optional<Order> findByIdAndUserIdWithDetails(@Param("id") Long id, @Param("userId") Long userId);

    @Query("""
            SELECT o FROM Order o
            JOIN FETCH o.user u
            JOIN FETCH o.shippingProvince p
            JOIN FETCH o.shippingDistrict d
            JOIN FETCH o.shippingWard w
            LEFT JOIN FETCH o.items i
            WHERE o.id = :id
            """)
    Optional<Order> findByIdWithDetails(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);

    List<Order> findByOrderStatusAndCreateDateBefore(OrderStatus status, LocalDateTime before);

    Page<Order> findAllByOrderByCreateDateDesc(Pageable pageable);

    Page<Order> findByOrderStatusOrderByCreateDateDesc(OrderStatus status, Pageable pageable);
}

