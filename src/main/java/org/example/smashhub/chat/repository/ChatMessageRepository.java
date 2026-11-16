package org.example.smashhub.chat.repository;

import org.example.smashhub.chat.entity.ChatMessage;
import org.example.smashhub.chat.enums.MessageType;
import org.example.smashhub.chat.enums.SenderRole;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    @EntityGraph(attributePaths = {"sender"})
    Optional<ChatMessage> findByConversationIdAndClientMessageId(Long conversationId, String clientMessageId);

    @EntityGraph(attributePaths = {"sender"})
    Page<ChatMessage> findByConversationId(Long conversationId, Pageable pageable);

    @EntityGraph(attributePaths = {"sender"})
    Page<ChatMessage> findByConversationIdAndVisibleToCustomerTrue(Long conversationId, Pageable pageable);

    @Modifying
    @Query("UPDATE ChatMessage m SET m.isRead = true WHERE m.conversation.id = :conversationId AND m.senderRole = :senderRole AND m.isRead = false")
    int markMessagesAsReadByRole(
            @Param("conversationId") Long conversationId,
            @Param("senderRole") SenderRole senderRole
    );

    boolean existsByConversationIdAndMessageTypeAndReferencedProductIdAndCreateDateAfter(
            Long conversationId,
            MessageType messageType,
            Long referencedProductId,
            LocalDateTime after
    );

    boolean existsByConversationIdAndMessageTypeAndReferencedOrderId(
            Long conversationId,
            MessageType messageType,
            Long referencedOrderId
    );
}

