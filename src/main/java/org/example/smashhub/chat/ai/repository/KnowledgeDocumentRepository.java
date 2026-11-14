package org.example.smashhub.chat.ai.repository;

import org.example.smashhub.chat.ai.entity.KnowledgeDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {

    List<KnowledgeDocument> findByStatus(String status);

    List<KnowledgeDocument> findByCategoryAndStatus(String category, String status);

    Optional<KnowledgeDocument> findByTitle(String title);

    Optional<KnowledgeDocument> findByContentHash(String contentHash);

    Page<KnowledgeDocument> findByStatusOrderByCreateDateDesc(String status, Pageable pageable);
}

