package org.example.smashhub.shipping.repository;

import org.example.smashhub.shipping.entity.Shipment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    Optional<Shipment> findByOrderId(Long orderId);

    Optional<Shipment> findByOrderCode(String orderCode);

    Optional<Shipment> findByClientOrderCode(String clientOrderCode);

    @Query("SELECT s FROM Shipment s JOIN FETCH s.order o WHERE s.orderCode = :orderCode")
    Optional<Shipment> findByOrderCodeWithOrder(@Param("orderCode") String orderCode);
}

