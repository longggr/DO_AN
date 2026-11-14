package org.example.smashhub.chat.mapper;

import org.example.smashhub.chat.dto.event.ConversationUpdateEvent;
import org.example.smashhub.chat.dto.response.ChatMessageResponse;
import org.example.smashhub.chat.dto.response.ConversationResponse;
import org.example.smashhub.chat.entity.ChatMessage;
import org.example.smashhub.chat.entity.Conversation;
import org.example.smashhub.chat.enums.SenderRole;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface ChatMapper {

    @Mapping(target = "customerId", source = "customer.id")
    @Mapping(target = "customerUsername", source = "customer.username")
    @Mapping(target = "customerEmail", source = "customer.email")
    @Mapping(target = "assignedAdminId", source = "assignedAdmin.id")
    @Mapping(target = "assignedAdminUsername", source = "assignedAdmin.username")
    @Mapping(target = "createdAt", source = "createDate")
    @Mapping(target = "updatedAt", source = "updateDate")
    @Mapping(target = "adminOnline", ignore = true)
    ConversationResponse toConversationResponse(Conversation conversation);

    @Mapping(target = "conversationId", source = "conversation.id")
    @Mapping(target = "senderId", source = "sender.id")
    @Mapping(target = "senderUsername", source = "sender.username")
    @Mapping(target = "createdAt", source = "createDate")
    @Mapping(target = "isRead", source = "read")
    @Mapping(target = "productCard", ignore = true)
    @Mapping(target = "orderCard", ignore = true)
    ChatMessageResponse toChatMessageResponse(ChatMessage message);

    @Mapping(target = "conversationId", source = "id")
    @Mapping(target = "customerId", source = "customer.id")
    @Mapping(target = "customerUsername", source = "customer.username")
    @Mapping(target = "assignedAdminId", source = "assignedAdmin.id")
    @Mapping(target = "assignedAdminUsername", source = "assignedAdmin.username")
    ConversationUpdateEvent toConversationUpdateEvent(Conversation conversation);

    @AfterMapping
    default void enrichChatMessageResponse(@MappingTarget ChatMessageResponse response, ChatMessage message) {
        if (message.getSenderRole() == SenderRole.BOT || message.getSenderRole() == SenderRole.AI) {
            response.setSenderDisplayName("Trợ lý AI");
        } else if (message.getSenderRole() == SenderRole.SYSTEM) {
            response.setSenderDisplayName("Hệ thống");
        } else if (message.getSender() != null && message.getSender().getUsername() != null) {
            response.setSenderDisplayName(message.getSender().getUsername());
        } else {
            response.setSenderDisplayName("Thành viên");
        }
    }
}
