package core.domain.chat.dto;

public record DeleteMessageRequest(
        String messageId,
        Long senderId
) {}