package org.example.smashhub.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
import org.example.smashhub.chat.enums.MessageType;
import org.example.smashhub.chat.enums.SenderRole;
import org.example.smashhub.common.persistence.BaseEntity;
import org.example.smashhub.user.entity.User;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(callSuper = true, exclude = {"conversation", "sender"})
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "chat_messages")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class ChatMessage extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", nullable = false)
    Conversation conversation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id", nullable = false)
    User sender;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_role", nullable = false, length = 20)
    SenderRole senderRole;

    @Column(name = "content", columnDefinition = "TEXT")
    String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false, length = 30)
    MessageType messageType;

    @Column(name = "attachment_url", length = 1024)
    String attachmentUrl;

    @Column(name = "referenced_product_id")
    Long referencedProductId;

    @Column(name = "referenced_order_id")
    Long referencedOrderId;

    @Column(name = "snapshot_json", columnDefinition = "TEXT")
    String snapshotJson;

    @Column(name = "client_message_id", nullable = false, length = 64)
    String clientMessageId;

    @Column(name = "ai_model", length = 50)
    String aiModel;

    @Column(name = "ai_sources", columnDefinition = "TEXT")
    String aiSources;

    @Enumerated(EnumType.STRING)
    @Column(name = "ai_feedback", length = 20)
    org.example.smashhub.chat.enums.AiFeedback aiFeedback;

    @Column(name = "is_read", nullable = false)
    @Builder.Default
    boolean isRead = false;
}

