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
@Table(name = "ai_faq_entries")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AiFaqEntry extends BaseEntity {

    @Column(name = "question_hash", nullable = false, unique = true, length = 64)
    String questionHash;

    @Column(name = "normalized_question", nullable = false, length = 500)
    String normalizedQuestion;

    @Column(name = "answer", nullable = false, columnDefinition = "TEXT")
    String answer;

    @Column(name = "category", nullable = false, length = 50)
    @Builder.Default
    String category = "GENERAL";

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    boolean active = true;

    @Column(name = "usage_count", nullable = false)
    @Builder.Default
    long usageCount = 0L;
}

