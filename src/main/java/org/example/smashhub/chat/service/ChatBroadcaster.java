package org.example.smashhub.chat.service;

public interface ChatBroadcaster {

    void broadcast(String destination, Object payload);

    void sendToUser(String username, String destination, Object payload);
}

