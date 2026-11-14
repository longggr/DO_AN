package org.example.smashhub.chat.exception;

import lombok.Getter;

@Getter
public class ChatRateLimitExceededException extends RuntimeException {

    private final long remainingSeconds;

    public ChatRateLimitExceededException(long remainingSeconds) {
        super(String.format("Bạn gửi tin quá nhanh. Vui lòng thử lại sau %d giây.", remainingSeconds));
        this.remainingSeconds = remainingSeconds;
    }
}

