package core.domain.chat.dto;


public record ImageDto(
        Long imageId,
        Long relatedId, // 여기서는 chatRoomId가 됨
        String imageUrl
) {}