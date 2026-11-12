package org.example.smashhub.cart.repository;

import org.example.smashhub.cart.entity.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    @Query("""
            SELECT c FROM CartItem c
            JOIN FETCH c.variant v
            JOIN FETCH v.color col
            JOIN FETCH col.product p
            WHERE c.user.id = :userId
            ORDER BY c.updateDate DESC
            """)
    List<CartItem> findByUserIdWithDetails(@Param("userId") Long userId);

    Optional<CartItem> findByUserIdAndVariantId(Long userId, Long variantId);

    boolean existsByUserIdAndVariantId(Long userId, Long variantId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM CartItem c WHERE c.user.id = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM CartItem c WHERE c.user.id = :userId AND c.variant.id = :variantId")
    int deleteByUserIdAndVariantId(@Param("userId") Long userId, @Param("variantId") Long variantId);

    long countByUserId(Long userId);

    @Query("SELECT COALESCE(SUM(c.quantity), 0) FROM CartItem c WHERE c.user.id = :userId")
    long sumQuantityByUserId(@Param("userId") Long userId);
}
