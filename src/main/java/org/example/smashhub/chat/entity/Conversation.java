package org.example.smashhub.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldDefaults;
import org.example.smashhub.chat.enums.ConversationStatus;
import org.example.smashhub.common.persistence.BaseEntity;
import org.example.smashhub.user.entity.User;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(callSuper = true, exclude = {"customer", "assignedAdmin"})
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "conversations")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class Conversation extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false, unique = true)
    User customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_admin_id")
    User assignedAdmin;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    ConversationStatus status = ConversationStatus.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(name = "handler_mode", nullable = false, length = 20)
    @Builder.Default
    org.example.smashhub.chat.enums.HandlerMode handlerMode = org.example.smashhub.chat.enums.HandlerMode.AI;

    @Column(name = "handoff_reason", length = 100)
    String handoffReason;

    @Column(name = "last_message_preview", length = 255)
    String lastMessagePreview;

    @Column(name = "last_message_at")
    LocalDateTime lastMessageAt;

    @Column(name = "customer_unread_count", nullable = false)
    @Builder.Default
    int customerUnreadCount = 0;

    @Column(name = "admin_unread_count", nullable = false)
    @Builder.Default
    int adminUnreadCount = 0;

    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    Long version = 0L;
}

