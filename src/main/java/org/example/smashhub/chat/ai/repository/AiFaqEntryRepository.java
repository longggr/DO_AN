package org.example.smashhub.chat.ai.repository;

import org.example.smashhub.chat.ai.entity.AiFaqEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AiFaqEntryRepository extends JpaRepository<AiFaqEntry, Long> {

    Optional<AiFaqEntry> findByQuestionHashAndActiveTrue(String questionHash);

    Page<AiFaqEntry> findByActive(boolean active, Pageable pageable);

    @Modifying
    @Query("UPDATE AiFaqEntry f SET f.usageCount = f.usageCount + 1 WHERE f.id = :id")
    int incrementUsageCount(@Param("id") Long id);
}

