package org.example.smashhub.chat.dto.response;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.example.smashhub.chat.enums.ConversationStatus;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class ConversationResponse implements Serializable {
    Long id;
    Long customerId;
    String customerUsername;
    String customerEmail;
    Long assignedAdminId;
    String assignedAdminUsername;
    ConversationStatus status;
    org.example.smashhub.chat.enums.HandlerMode handlerMode;
    String lastMessagePreview;
    LocalDateTime lastMessageAt;
    int customerUnreadCount;
    int adminUnreadCount;
    boolean adminOnline;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}

