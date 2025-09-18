package core.domain.chat.service;


import core.domain.chat.dto.GroupChatDetailResponse;
import core.domain.chat.dto.GroupChatMainResponse;
import core.domain.chat.dto.GroupChatSearchResponse;
import core.domain.chat.entity.ChatParticipant;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatParticipantRepository;
import core.domain.chat.repository.ChatRoomRepository;
import core.domain.user.entity.User;
import core.global.enums.ChatParticipantStatus;
import core.global.enums.ErrorCode;
import core.global.enums.ImageType;
import core.global.exception.BusinessException;
import core.global.image.entity.Image;
import core.global.image.repository.ImageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GroupChatService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatParticipantRepository participantRepo;
    private final ImageRepository imageRepository;

    public GroupChatDetailResponse getGroupChatDetails(Long chatRoomId) {
        ChatRoom chatRoom = chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));

        List<ChatParticipant> activeParticipants = chatRoom.getParticipants().stream()
                .filter(participant -> participant.getStatus() == ChatParticipantStatus.ACTIVE)
                .collect(Collectors.toList());

        String roomImageUrl = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(
                ImageType.CHAT_ROOM, chatRoom.getId()
        ).map(Image::getUrl).orElse(null);

        Long ownerId = chatRoom.getOwner().getId();
        String ownerImageUrl = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(
                ImageType.USER,
                ownerId
        ).map(Image::getUrl).orElse(null);

        List<String> otherParticipantsImageUrls = activeParticipants.stream()
                .filter(participant -> !participant.getUser().getId().equals(ownerId))
                .map(participant -> imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(
                        ImageType.USER,
                        participant.getUser().getId()
                ).map(Image::getUrl).orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        return GroupChatDetailResponse.from(
                chatRoom,
                roomImageUrl,
                activeParticipants.size(),
                otherParticipantsImageUrls,
                ownerImageUrl
        );
    }

    /**
     * 현재 사용자를 지정된 그룹 채팅방에 추가합니다.
     *
     * @param roomId 그룹 채팅방 ID
     * @param userId 참여를 요청하는 사용자의 ID
     */
    @Transactional
    public void joinGroupChat(Long roomId, Long userId) {

        ChatRoom room = chatRoomRepo.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));

        if (!room.getGroup()) {
            throw new BusinessException(ErrorCode.CHAT_NOT_GROUP);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
                .ifPresentOrElse(
                        participant -> {
                            if (participant.getStatus() == ChatParticipantStatus.ACTIVE) {
                                throw new BusinessException(ErrorCode.ALREADY_CHAT_PARTICIPANT);
                            } else {
                                participant.reJoin();
                            }
                        },
                        () -> {
                            ChatParticipant newParticipant = new ChatParticipant(room, user);
                            room.addParticipant(newParticipant);
                            chatParticipantRepository.save(newParticipant);
                        }
                );
    }

    /**
     * 그룹 채팅방을 이름 키워드로 검색합니다.
     * @param keyword 검색 키워드
     * @return 검색 결과 DTO 목록
     */
    public List<GroupChatSearchResponse> searchGroupChatRooms(String keyword) {
        List<ChatRoom> chatRooms = chatRoomRepository.findGroupChatRoomsByKeyword(keyword);

        return chatRooms.stream()
                .map(chatRoom -> {
                    String roomImageUrl = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(
                            ImageType.CHAT_ROOM, chatRoom.getId()
                    ).map(Image::getUrl).orElse(null);

                    int participantCount = chatRoom.getParticipants().size();

                    return GroupChatSearchResponse.from(chatRoom, roomImageUrl, participantCount);
                })
                .collect(Collectors.toList());
    }


    @Transactional
    public List<GroupChatMainResponse> getLatestGroupChats(Long lastChatRoomId) {
        List<ChatRoom> latestRooms;

        if (lastChatRoomId == null) {
            latestRooms = chatRoomRepository.findTop10ByGroupTrueOrderByCreatedAtDesc();
        } else {
            latestRooms = chatRoomRepository.findTop10ByGroupTrueAndIdLessThanOrderByCreatedAtDesc(lastChatRoomId);
        }

        return latestRooms.stream()
                .map(this::toGroupChatMainResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public List<GroupChatMainResponse> getPopularGroupChats(int limit) {
        List<ChatRoom> popularRooms = chatRoomRepository.findTopByGroupTrueOrderByParticipantCountDesc(limit);
        return popularRooms.stream()
                .map(this::toGroupChatSearchResponse)
                .collect(Collectors.toList());
    }
    private GroupChatMainResponse toGroupChatSearchResponse(ChatRoom chatRoom) {
        String roomImageUrl = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(
                        ImageType.CHAT_ROOM, chatRoom.getId())
                .map(Image::getUrl)
                .orElse(null);
        String userCount = String.valueOf(chatRoom.getParticipants().size());
        return new GroupChatMainResponse(
                chatRoom.getId(),
                chatRoom.getRoomName(),
                chatRoom.getDescription(),
                roomImageUrl,
                userCount
        );
    }
    private GroupChatMainResponse toGroupChatMainResponse(ChatRoom chatRoom) {
        String roomImageUrl = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.CHAT_ROOM, chatRoom.getId())
                .map(Image::getUrl)
                .orElse(null);
        String userCount = String.valueOf(chatRoom.getParticipants().size());
        return new GroupChatMainResponse(
                chatRoom.getId(),
                chatRoom.getRoomName(),
                chatRoom.getDescription(),
                roomImageUrl,
                userCount
        );
    }
}