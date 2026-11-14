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
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "knowledge_document")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class KnowledgeDocument extends BaseEntity {

    @Column(name = "title", nullable = false, length = 255)
    String title;

    @Column(name = "category", nullable = false, length = 50)
    @Builder.Default
    String category = "GENERAL";

    @Column(name = "content_markdown", nullable = false, columnDefinition = "MEDIUMTEXT")
    String contentMarkdown;

    @Column(name = "content_hash", nullable = false, length = 64)
    String contentHash;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    String status = "ACTIVE";
}

