package org.example.smashhub.chat.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.example.smashhub.chat.enums.MessageType;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class SendMessageRequest {

    @NotNull(message = "conversationId must not be null")
    Long conversationId;

    @NotNull(message = "clientMessageId must not be null")
    @Size(min = 1, max = 64, message = "clientMessageId length must be between 1 and 64")
    String clientMessageId;

    @Size(max = 2000, message = "content must not exceed 2000 characters")
    String content;

    @NotNull(message = "messageType must not be null")
    MessageType messageType;

    String attachmentUrl;

    Long referencedProductId;

    Long referencedOrderId;
}

