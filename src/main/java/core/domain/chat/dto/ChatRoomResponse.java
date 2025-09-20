package core.domain.chat.dto;

import core.domain.chat.entity.ChatRoom;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ChatRoomResponse(
        Long id,
        Boolean isGroup,
        Instant createdAt,
        List<ChatParticipantResponse> participants
) {
    public static ChatRoomResponse from(ChatRoom room, Map<Long, UserResponseDto> userInfoMap) {
        List<ChatParticipantResponse> participantResponses = room.getParticipants().stream()
                .map(p -> {
                    UserResponseDto userDto = userInfoMap.getOrDefault(p.getUserId(), UserResponseDto.unknown());
                    return ChatParticipantResponse.from(p, userDto);
                })
                .toList();

        return new ChatRoomResponse(
                room.getId(),
                room.getGroup(),
                room.getCreatedAt(),
                participantResponses
        );
    };
}