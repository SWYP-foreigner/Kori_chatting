package core.domain.chat.dto;

import core.domain.chat.entity.ChatMessage;
import core.domain.chat.entity.ChatRoom;
import core.domain.user.entity.User;
import core.global.enums.ImageType;


import java.time.Instant;
public record ChatMessageFirstResponse(
        String id,
        Long roomId,
        Long senderId,
        String content,
        Instant sentAt,
        String senderFirstName,
        String senderLastName,
        String senderImageUrl
) {
    /**
     * [리팩토링 후]
     * 이 팩토리 메서드는 순수 데이터 객체인 ChatMessage와 UserResponseDto만 받아서
     * 최종 DTO를 조립하는 역할만 수행합니다.
     * 더 이상 데이터베이스에 접근하지 않습니다.
     */
    public static ChatMessageFirstResponse from(ChatMessage message, UserResponseDto sender) {
        return new ChatMessageFirstResponse(
                message.getId(),
                message.getChatRoomId(),
                sender.userId(),
                message.getContent(),
                message.getSentAt(),
                sender.firstName(),
                sender.lastName(),
                sender.ImageUrl()
        );
    }
}