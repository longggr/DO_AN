package org.example.smashhub.chat.dto.response;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.example.smashhub.chat.enums.AiFeedback;
import org.example.smashhub.chat.enums.MessageType;
import org.example.smashhub.chat.enums.SenderRole;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class ChatMessageResponse implements Serializable {
    Long id;
    Long conversationId;
    Long senderId;
    String senderUsername;
    String senderDisplayName;
    SenderRole senderRole;
    String content;
    MessageType messageType;
    String attachmentUrl;
    Long referencedProductId;
    Long referencedOrderId;
    ProductSummaryCardDto productCard;
    OrderSummaryCardDto orderCard;
    String clientMessageId;
    String aiModel;
    String aiSources;
    AiFeedback aiFeedback;
    boolean isRead;
    boolean visibleToCustomer = true;
    LocalDateTime createdAt;
}
