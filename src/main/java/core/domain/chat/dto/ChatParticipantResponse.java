package core.domain.chat.dto;

import core.domain.chat.entity.ChatParticipant;
import core.global.enums.ChatParticipantStatus;

import java.time.Instant;

import core.domain.chat.entity.ChatParticipant;
import core.domain.chat.dto.UserResponseDto; // UserResponseDto import
import core.global.enums.ChatParticipantStatus;

import java.time.Instant;

/**
 * 채팅방 참여자 한 명의 정보를 담는 DTO입니다.
 *
 * @param id       ChatParticipant 레코드의 고유 ID
 * @param userId   사용자의 고유 ID
 * @param userName 사용자의 전체 이름 (firstName + lastName)
 * @param status   채팅방에서의 참여 상태 (ACTIVE, LEFT 등)
 */
public record ChatParticipantResponse(
        Long id,
        Long userId,
        String userName,
        ChatParticipantStatus status
) {
    /**
     * ChatParticipant 엔티티와 API로 조회한 UserResponseDto를 조합하여
     * ChatParticipantResponse DTO를 생성합니다.
     *
     * @param participant ChatParticipant 엔티티
     * @param userDto     API로 조회한 사용자의 상세 정보 DTO
     * @return ChatParticipantResponse
     */
    public static ChatParticipantResponse from(ChatParticipant participant, UserResponseDto userDto) {
        String fullName = (userDto.firstName() + " " + userDto.lastName()).trim();

        return new ChatParticipantResponse(
                participant.getId(),
                participant.getUserId(),
                fullName,
                participant.getStatus()
        );
    }
}