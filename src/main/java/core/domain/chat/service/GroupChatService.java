package core.domain.chat.service;


import core.domain.chat.client.UserClient;
import core.domain.chat.dto.*;
import core.domain.chat.entity.ChatParticipant;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatParticipantRepository;
import core.domain.chat.repository.ChatRoomRepository;
import core.domain.user.entity.User;
import core.global.enums.ChatParticipantStatus;
import core.global.enums.ErrorCode;
import core.global.enums.ImageType;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GroupChatService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatParticipantRepository participantRepo;
    private final UserClient userClient;
    private final ChatParticipantRepository chatParticipantRepository;

    /**
     * 그룹 채팅방의 상세 정보를 조회합니다.
     * * @apiNote 이 메서드는 다음과 같은 순서로 동작합니다.
     * 1. 로컬 DB에서 채팅방(ChatRoom)과 활성 참여자(Participant) 목록을 조회합니다.
     * 2. Main Service에 두 번의 Bulk API를 호출합니다:
     * - 채팅방 대표 이미지 1개 조회
     * - 모든 활성 참여자들의 상세 정보(이름, 프로필 이미지 등) 일괄 조회
     * 3. 조회된 모든 정보를 조합하여 최종 응답 DTO를 생성합니다.
     * * @param chatRoomId 조회할 그룹 채팅방의 ID
     * @return GroupChatDetailResponse 그룹 채팅방 상세 정보
     * @throws BusinessException 채팅방을 찾을 수 없을 때 발생
     */
    @Transactional(readOnly = true)
    public GroupChatDetailResponse getGroupChatDetails(Long chatRoomId) {
        ChatRoom chatRoom = chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));

        List<ChatParticipant> activeParticipants = chatRoom.getParticipants().stream()
                .filter(p -> p.getStatus() == ChatParticipantStatus.ACTIVE)
                .toList();

        if (!chatRoom.getGroup()) {
            throw new BusinessException(ErrorCode.CHAT_NOT_GROUP);
        }

        Long ownerId = chatRoom.getOwnerId();

        String roomImageUrl = userClient.getImagesForChatRooms(List.of(chatRoomId)).stream()
                .findFirst()
                .map(ImageDto::imageUrl)
                .orElse(null);

        List<Long> userIds = activeParticipants.stream().map(ChatParticipant::getUserId).toList();
        Map<Long, UserResponseDto> userInfoMap = userClient.getUsersInfo(userIds).stream()
                .collect(Collectors.toMap(UserResponseDto::userId, Function.identity()));

        String ownerImageUrl = userInfoMap.getOrDefault(ownerId, UserResponseDto.unknown()).ImageUrl();

        List<String> otherParticipantsImageUrls = activeParticipants.stream()
                .map(p -> userInfoMap.get(p.getUserId()))
                .filter(Objects::nonNull)
                .filter(user -> !user.userId().equals(ownerId))
                .map(UserResponseDto::ImageUrl)
                .filter(Objects::nonNull)
                .toList();

        return GroupChatDetailResponse.from(
                chatRoom,
                roomImageUrl,
                activeParticipants.size(),
                otherParticipantsImageUrls,
                ownerImageUrl
        );
    }

    // ChatRoomService.java

    /**
     * 현재 사용자를 지정된 그룹 채팅방에 추가(참여)시킵니다.
     *
     * @apiNote 이 메서드는 다음과 같은 순서로 동작합니다.
     * 1. 채팅방이 존재하는지, 그룹 채팅방이 맞는지 확인합니다.
     * 2. 사용자가 이미 참여자인지 확인합니다.
     * - 이미 'ACTIVE' 상태라면 예외를 발생시킵니다.
     * - 'LEFT' 상태라면, 상태를 'ACTIVE'로 변경하여 다시 참여시킵니다 (reJoin).
     * 3. 참여자가 아니라면, 새로운 ChatParticipant 레코드를 생성하여 DB에 저장합니다.
     *
     * @param roomId  그룹 채팅방 ID
     * @param userId  참여를 요청하는 사용자의 ID
     * @throws BusinessException 채팅방/유저를 찾을 수 없거나, 그룹 채팅이 아니거나, 이미 참여 중일 때 발생
     */
    @Transactional
    public void joinGroupChat(Long roomId, Long userId) {

        ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));

        if (!room.getGroup()) {
            throw new BusinessException(ErrorCode.CHAT_NOT_GROUP);
        }
        participantRepo.findByChatRoomIdAndUserId(roomId, userId)
                .ifPresentOrElse(
                        participant -> {
                            if (participant.getStatus() == ChatParticipantStatus.ACTIVE) {
                                throw new BusinessException(ErrorCode.ALREADY_CHAT_PARTICIPANT);
                            } else {
                                participant.reJoin();
                            }
                        },
                        () -> {
                            ChatParticipant newParticipant = new ChatParticipant(room, userId);
                            participantRepo.save(newParticipant);
                        }
                );
    }

    // ChatRoomService.java

    /**
     * 이름 키워드로 공개된 그룹 채팅방을 검색합니다.
     *
     * @apiNote 이 메서드는 다음과 같은 순서로 동작합니다.
     * 1. 로컬 DB에서 키워드에 맞는 그룹 채팅방 목록을 검색합니다.
     * 2. 검색된 모든 채팅방의 ID를 수집합니다.
     * 3. Main Service에 Bulk API를 한 번 호출하여 모든 채팅방의 대표 이미지를 일괄 조회합니다.
     * 4. 조회된 정보를 조합하여 최종 응답 DTO 목록을 생성합니다.
     *
     * @param keyword 검색할 채팅방 이름 키워드
     * @return List<GroupChatSearchResponse> 검색된 그룹 채팅방 목록
     */
    @Transactional(readOnly = true)
    public List<GroupChatSearchResponse> searchGroupChatRooms(String keyword) {
        List<ChatRoom> chatRooms = chatRoomRepository.findGroupChatRoomsByKeyword(keyword);
        if (chatRooms.isEmpty()) {
            return List.of();
        }
        List<Long> roomIds = chatRooms.stream().map(ChatRoom::getId).toList();
        Map<Long, String> roomImageMap = userClient.getImagesForChatRooms(roomIds).stream()
                .collect(Collectors.toMap(ImageDto::relatedId, ImageDto::imageUrl));

        return chatRooms.stream()
                .map(chatRoom -> {
                    String roomImageUrl = roomImageMap.get(chatRoom.getId());
                    int participantCount = chatRoom.getParticipants().size();

                    return GroupChatSearchResponse.from(chatRoom, roomImageUrl, participantCount);
                })
                .collect(Collectors.toList());
    }

        /**
         * @apiNote 최신 그룹 채팅방 목록을 무한 스크롤 방식으로 조회합니다.
         */
        @Transactional(readOnly = true)
        public List<GroupChatMainResponse> getLatestGroupChats(Long lastChatRoomId) {
            List<ChatRoom> latestRooms;

            if (lastChatRoomId == null) {
                latestRooms = chatRoomRepository.findTop10ByGroupTrueOrderByCreatedAtDesc();
            } else {
                latestRooms = chatRoomRepository.findTop10ByGroupTrueAndIdLessThanOrderByCreatedAtDesc(lastChatRoomId);
            }

            // 공통 헬퍼 메서드를 호출하여 DTO로 변환 후 반환
            return enrichAndMapToGroupChatResponses(latestRooms);
        }

        /**
         * @apiNote 참여자 수가 많은 인기 그룹 채팅방 목록을 조회합니다.
         */
        @Transactional(readOnly = true)
        public List<GroupChatMainResponse> getPopularGroupChats(int limit) {
            List<ChatRoom> popularRooms = chatRoomRepository.findPopularGroupChats(PageRequest.of(0, limit));

            return enrichAndMapToGroupChatResponses(popularRooms);
        }

        /**
         * @apiNote [공통 헬퍼 메서드] ChatRoom 리스트를 받아 이미지 정보를 채우고 DTO 리스트로 변환합니다.
         * N+1 API 호출 문제를 해결하기 위해 이 메서드에서 이미지 정보를 일괄 조회합니다.
         */
        private List<GroupChatMainResponse> enrichAndMapToGroupChatResponses(List<ChatRoom> rooms) {
            if (rooms.isEmpty()) {
                return List.of();
            }

            List<Long> roomIds = rooms.stream().map(ChatRoom::getId).toList();
            Map<Long, String> roomImageMap = userClient.getImagesForChatRooms(roomIds).stream()
                    .collect(Collectors.toMap(ImageDto::relatedId, ImageDto::imageUrl));

            return rooms.stream()
                    .map(chatRoom -> {
                        String roomImageUrl = roomImageMap.get(chatRoom.getId());
                        String userCount = String.valueOf(chatRoom.getParticipants().size());

                        return new GroupChatMainResponse(
                                chatRoom.getId(),
                                chatRoom.getRoomName(),
                                chatRoom.getDescription(),
                                roomImageUrl,
                                userCount
                        );
                    })
                    .collect(Collectors.toList());
        }
// ChatRoomService.java

    /**
     * 새로운 그룹 채팅방을 생성합니다.
     *
     * @apiNote 이 메서드는 다음과 같은 순서로 동작합니다.
     * 1. 로컬 DB에 ChatRoom과 생성자(Owner)의 ChatParticipant 정보를 저장합니다.
     * 2. 요청에 채팅방 이미지 URL이 포함된 경우, UserClient를 통해 Main Service에 이미지 저장을 요청합니다.
     *
     * @param userId  채팅방을 생성하는 사용자의 ID (방장)
     * @param request 채팅방 이름, 설명, 이미지 URL 등이 담긴 요청 DTO
     */
    @Transactional
    public void createGroupChatRoom(Long userId, CreateGroupChatRequest request) {
        ChatRoom newRoom = new ChatRoom(
                true,
                Instant.now(),
                request.roomName(),
                request.description(),
                userId
        );
        ChatRoom savedRoom = chatRoomRepository.save(newRoom);
        ChatParticipant ownerParticipant = new ChatParticipant(savedRoom, userId);
        chatParticipantRepository.save(ownerParticipant);

        if (request.roomImageUrl() != null && !request.roomImageUrl().isBlank()) {
            userClient.upsertChatRoomImage(
                    new UpsertChatRoomImageRequest(savedRoom.getId(), request.roomImageUrl())
            );
        }
    }
}