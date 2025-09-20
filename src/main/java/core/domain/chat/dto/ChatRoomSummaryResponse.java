package core.domain.chat.dto;

import core.domain.chat.entity.ChatParticipant;
import core.domain.chat.entity.ChatRoom;
import core.domain.user.entity.User;
import core.global.enums.ImageType;


import java.time.Instant;


import core.domain.chat.entity.ChatRoom;
import java.time.Instant;

public record ChatRoomSummaryResponse(
        Long roomId,
        String roomName,
        String lastMessageContent,
        Instant lastMessageTime,
        String roomImageUrl,
        int unreadCount,
        int participantCount
) {
    /**
     * [수정 후]
     * 서비스 계층에서 미리 계산한 채팅방 이름과 이미지 URL을 직접 받도록 수정합니다.
     * 이 DTO는 이제 데이터를 담는 역할에만 충실합니다.
     */
    public static ChatRoomSummaryResponse from(
            ChatRoom room,
            String lastMessageContent,
            Instant lastMessageTime,
            int unreadCount,
            String summaryRoomName,
            String summaryRoomImageUrl
    ) {
        return new ChatRoomSummaryResponse(
                room.getId(),
                summaryRoomName, // ◀ 파라미터로 받은 이름 사용
                lastMessageContent,
                lastMessageTime,
                summaryRoomImageUrl, // ◀ 파라미터로 받은 URL 사용
                unreadCount,
                room.getParticipants().size()
        );
    }
}