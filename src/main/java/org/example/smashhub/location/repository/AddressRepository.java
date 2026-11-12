package org.example.smashhub.location.repository;

import org.example.smashhub.common.enums.AddressPriority;
import org.example.smashhub.location.entity.Address;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AddressRepository extends JpaRepository<Address, Long> {

    @Query("""
            SELECT a FROM Address a
            JOIN FETCH a.province p
            JOIN FETCH a.district d
            JOIN FETCH a.ward w
            WHERE a.user.id = :userId
            ORDER BY a.primaryAddress ASC, a.updateDate DESC
            """)
    List<Address> findByUserIdWithDetails(@Param("userId") Long userId);

    @Query("""
            SELECT a FROM Address a
            JOIN FETCH a.province p
            JOIN FETCH a.district d
            JOIN FETCH a.ward w
            WHERE a.id = :id AND a.user.id = :userId
            """)
    Optional<Address> findByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    Optional<Address> findByUserIdAndPrimaryAddress(Long userId, AddressPriority primaryAddress);

    long countByUserId(Long userId);

    @Modifying
    @Query("UPDATE Address a SET a.primaryAddress = :secondary WHERE a.user.id = :userId")
    void resetAllPrimaryForUser(@Param("userId") Long userId, @Param("secondary") AddressPriority secondary);
}

