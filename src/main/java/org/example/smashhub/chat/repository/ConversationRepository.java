package org.example.smashhub.chat.repository;

import org.example.smashhub.chat.entity.Conversation;
import org.example.smashhub.chat.enums.ConversationStatus;
import org.example.smashhub.user.entity.User;
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
public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    @EntityGraph(attributePaths = {"customer", "assignedAdmin"})
    Optional<Conversation> findByCustomerId(Long customerId);

    @EntityGraph(attributePaths = {"customer", "assignedAdmin"})
    Optional<Conversation> findWithDetailsById(Long id);

    @Query("SELECT c.customer.id FROM Conversation c WHERE c.id = :id")
    Optional<Long> findCustomerIdByConversationId(@Param("id") Long id);

    @EntityGraph(attributePaths = {"customer", "assignedAdmin"})
    @Query("SELECT c FROM Conversation c WHERE " +
           "(:status IS NULL OR c.status = :status) AND " +
           "(:unreadOnly = false OR c.adminUnreadCount > 0)")
    Page<Conversation> findForAdmin(
            @Param("status") ConversationStatus status,
            @Param("unreadOnly") boolean unreadOnly,
            Pageable pageable
    );

    @Modifying
    @Query("UPDATE Conversation c SET " +
           "c.lastMessagePreview = :preview, " +
           "c.lastMessageAt = :now, " +
           "c.adminUnreadCount = c.adminUnreadCount + 1, " +
           "c.status = CASE WHEN c.status = org.example.smashhub.chat.enums.ConversationStatus.RESOLVED " +
           "THEN org.example.smashhub.chat.enums.ConversationStatus.OPEN ELSE c.status END " +
           "WHERE c.id = :id")
    int updateOnCustomerMessage(
            @Param("id") Long id,
            @Param("preview") String preview,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Query(value = "UPDATE conversations SET " +
           "last_message_preview = :preview, " +
           "last_message_at = :now, " +
           "customer_unread_count = customer_unread_count + 1, " +
           "status = CASE WHEN status = 'OPEN' THEN 'HANDLING' ELSE status END, " +
           "assigned_admin_id = COALESCE(assigned_admin_id, :adminId), " +
           "handler_mode = 'HUMAN' " +
           "WHERE id = :id", nativeQuery = true)
    int updateOnAdminMessage(
            @Param("id") Long id,
            @Param("preview") String preview,
            @Param("now") LocalDateTime now,
            @Param("adminId") Long adminId
    );

    @Modifying
    @Query("UPDATE Conversation c SET " +
           "c.lastMessagePreview = :preview, " +
           "c.lastMessageAt = :now, " +
           "c.customerUnreadCount = c.customerUnreadCount + 1 " +
           "WHERE c.id = :id")
    int updateOnSystemMessage(
            @Param("id") Long id,
            @Param("preview") String preview,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Query("UPDATE Conversation c SET " +
           "c.lastMessagePreview = :preview, " +
           "c.lastMessageAt = :now, " +
           "c.customerUnreadCount = c.customerUnreadCount + 1 " +
           "WHERE c.id = :id")
    int updateOnAiMessage(
            @Param("id") Long id,
            @Param("preview") String preview,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Query("UPDATE Conversation c SET c.handlerMode = :mode WHERE c.id = :id")
    int updateHandlerMode(
            @Param("id") Long id,
            @Param("mode") org.example.smashhub.chat.enums.HandlerMode mode
    );

    @Modifying
    @Query("UPDATE Conversation c SET c.status = :status WHERE c.id = :id")
    int updateStatus(
            @Param("id") Long id,
            @Param("status") ConversationStatus status
    );

    @Modifying
    @Query("UPDATE Conversation c SET c.status = :status, c.handlerMode = :mode WHERE c.id = :id")
    int updateStatusAndHandlerMode(
            @Param("id") Long id,
            @Param("status") ConversationStatus status,
            @Param("mode") org.example.smashhub.chat.enums.HandlerMode mode
    );

    @Modifying
    @Query("UPDATE Conversation c SET c.adminUnreadCount = 0 WHERE c.id = :id")
    int resetAdminUnreadCount(@Param("id") Long id);

    @Modifying
    @Query("UPDATE Conversation c SET c.customerUnreadCount = 0 WHERE c.id = :id")
    int resetCustomerUnreadCount(@Param("id") Long id);
}

