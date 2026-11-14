package org.example.smashhub.chat.ai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(callSuper = true, exclude = {"embeddingJson"})
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "ai_knowledge_chunks")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AiKnowledgeChunk extends BaseEntity {

    @Column(name = "entity_type", nullable = false, length = 30)
    String entityType; // PRODUCT, FAQ, POLICY, BRAND

    @Column(name = "entity_id")
    Long entityId;

    @Column(name = "content_hash", nullable = false, length = 64)
    String contentHash;

    @Column(name = "chunk_text", nullable = false, columnDefinition = "TEXT")
    String chunkText;

    @Column(name = "embedding_json", columnDefinition = "MEDIUMTEXT")
    String embeddingJson;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    String status = "ACTIVE";
}

