package org.example.smashhub.chat.ai.repository;

import org.example.smashhub.chat.ai.entity.AiKnowledgeChunk;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AiKnowledgeChunkRepository extends JpaRepository<AiKnowledgeChunk, Long> {

    Optional<AiKnowledgeChunk> findByEntityTypeAndEntityIdAndContentHash(
            String entityType, Long entityId, String contentHash
    );

    List<AiKnowledgeChunk> findByEntityTypeAndEntityId(String entityType, Long entityId);

    void deleteByEntityTypeAndEntityId(String entityType, Long entityId);

    Page<AiKnowledgeChunk> findByStatus(String status, Pageable pageable);
}

