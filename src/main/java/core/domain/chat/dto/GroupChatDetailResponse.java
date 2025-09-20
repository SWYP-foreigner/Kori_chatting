package core.domain.chat.dto;

import core.domain.chat.entity.ChatRoom;

import java.util.List;

import core.domain.chat.entity.ChatRoom;
import core.domain.chat.dto.UserResponseDto;

import java.util.List;

public record GroupChatDetailResponse(
        Long chatRoomId,
        String roomName,
        String description,
        Long ownerId,
        String ownerFirstName,
        String ownerLastName,
        String ownerImageUrl,
        String roomImageUrl,
        int participantCount,
        List<String> otherParticipantsImageUrls
) {
    /**
     * [수정된 from 메서드]
     * ChatRoom 엔티티와 함께, API로 미리 조회한 방장의 상세 정보를 받아서 DTO를 생성합니다.
     */
    public static GroupChatDetailResponse from(
            ChatRoom chatRoom,
            String roomImageUrl,
            int participantCount,
            List<String> otherParticipantsImageUrls,
            UserResponseDto ownerInfo
    ) {
        return new GroupChatDetailResponse(
                chatRoom.getId(),
                chatRoom.getRoomName(),
                chatRoom.getDescription(),
                ownerInfo.userId(),
                ownerInfo.firstName(),
                ownerInfo.lastName(),
                ownerInfo.ImageUrl(),
                roomImageUrl,
                participantCount,
                otherParticipantsImageUrls
        );
    }
}