package org.example.smashhub.chat.dto.request;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.example.smashhub.chat.enums.ConversationStatus;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class ConversationFilterRequest {
    ConversationStatus status;
    @Builder.Default
    boolean unreadOnly = false;
    @Builder.Default
    int page = 0;
    @Builder.Default
    int size = 20;
}

