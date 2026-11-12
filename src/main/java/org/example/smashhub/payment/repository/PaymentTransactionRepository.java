package org.example.smashhub.payment.repository;

import jakarta.persistence.LockModeType;
import org.example.smashhub.payment.entity.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    Optional<PaymentTransaction> findByTxnRef(String txnRef);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PaymentTransaction p JOIN FETCH p.order o WHERE p.txnRef = :txnRef")
    Optional<PaymentTransaction> findByTxnRefForUpdate(@Param("txnRef") String txnRef);

    List<PaymentTransaction> findByOrderIdOrderByCreateDateDesc(Long orderId);

    List<PaymentTransaction> findByOrderIdAndStatus(Long orderId, String status);

    Optional<PaymentTransaction> findTopByOrderIdOrderByCreateDateDesc(Long orderId);
}

