package core.domain.chat.service;

import core.domain.chat.client.UserClient;
import core.domain.chat.dto.*;
import core.domain.chat.entity.ChatMessage;
import core.domain.chat.entity.ChatParticipant;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.chat.repository.ChatParticipantRepository;
import core.domain.chat.repository.ChatRoomRepository;
import core.domain.user.entity.User;
import core.global.enums.ChatParticipantStatus;
import core.global.enums.ErrorCode;
import core.global.enums.ImageType;
import core.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatRoomService {

    private final ChatRoomRepository chatRoomRepo;
    private final ChatParticipantRepository chatParticipantRepository;
    private final ChatMessageRepository chatMessageRepo;
    private final UserClient userClient;

    private final ChatMessageService chatMessageService;
    private final ChatRoomRepository chatRoomRepository;

    @Transactional(readOnly = true)
    public List<ChatRoomSummaryResponse> getMyAllChatRoomSummaries(Long userId) {
        List<ChatRoom> rooms = chatRoomRepo.findActiveChatRoomsByUserId(userId, ChatParticipantStatus.ACTIVE);
        if (rooms.isEmpty()) {
            return List.of();
        }

        List<Long> opponentUserIds = new ArrayList<>();
        List<Long> groupRoomIds = new ArrayList<>();
        Map<Long, Long> roomToOpponentIdMap = new HashMap<>();

        for (ChatRoom room : rooms) {
            if (room.getGroup()) {
                groupRoomIds.add(room.getId());
            } else {
                room.getParticipants().stream()
                        .map(ChatParticipant::getUserId)
                        .filter(participantId -> !participantId.equals(userId))
                        .findFirst()
                        .ifPresent(opponentId -> {
                            opponentUserIds.add(opponentId);
                            roomToOpponentIdMap.put(room.getId(), opponentId);
                        });
            }
        }

        Map<Long, UserResponseDto> opponentInfoMap = userClient.getUsersInfo(opponentUserIds).stream()
                .collect(Collectors.toMap(UserResponseDto::userId, Function.identity()));

        Map<Long, String> groupRoomImageMap = userClient.getImagesForChatRooms(groupRoomIds).stream()
                .collect(Collectors.toMap(ImageDto::relatedId, ImageDto::imageUrl));

        return rooms.stream()
                .map(room -> {
                    String lastMessageContent = chatMessageService.getLastMessageContent(room.getId());
                    Instant lastMessageTime = chatMessageService.getLastMessageTime(room.getId());
                    int unreadCount = chatMessageService.countUnreadMessages(room.getId(), userId);
                    String roomName;
                    String roomImageUrl;

                    if (room.getGroup()) {
                        roomName = room.getRoomName();
                        roomImageUrl = groupRoomImageMap.get(room.getId());
                    } else {
                        Long opponentId = roomToOpponentIdMap.get(room.getId());
                        UserResponseDto opponentInfo = opponentInfoMap.getOrDefault(opponentId, UserResponseDto.unknown());
                        roomName = opponentInfo.firstName() + " " + opponentInfo.lastName();
                        roomImageUrl = opponentInfo.ImageUrl();
                    }

                    return new ChatRoomSummaryResponse(
                            room.getId(),
                            roomName,
                            lastMessageContent,
                            lastMessageTime,
                            roomImageUrl,
                            unreadCount,
                            room.getParticipants().size()
                    );
                })
                .sorted(Comparator.comparing(ChatRoomSummaryResponse::lastMessageTime, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    @Transactional
    public ChatRoomResponse createRoom(Long currentUserId, Long otherUserId) {
        ChatRoom room;
        List<ChatRoom> existingRooms  = chatRoomRepo.findOneToOneRoomByParticipantIds(currentUserId, otherUserId);
        if (existingRooms .size() > 1) {
            log.warn("중복된 1:1 채팅방이 발견되었습니다. 사용자 ID: {}, {}", currentUserId, otherUserId);
        }
        if (!existingRooms.isEmpty()) {
            room = existingRooms .getFirst();
            handleExistingRoom(room, currentUserId);
        } else {
            room = createNewOneToOneChatRoom(currentUserId, otherUserId);
        }

        List<Long> participantUserIds = room.getParticipants().stream()
                .map(ChatParticipant::getUserId).toList();
        Map<Long, UserResponseDto> userInfoMap = userClient.getUsersInfo(participantUserIds).stream()
                .collect(Collectors.toMap(UserResponseDto::userId, Function.identity()));

        return ChatRoomResponse.from(room, userInfoMap);
    }

    private ChatRoom handleExistingRoom(ChatRoom room, Long currentUserId) {
        Optional<ChatParticipant> currentParticipant = room.getParticipants().stream()
                .filter(p -> p.getUserId().equals(currentUserId))
                .findFirst();

        if (currentParticipant.isPresent() && currentParticipant.get().getStatus() == ChatParticipantStatus.LEFT) {
            currentParticipant.get().reJoin();
        }
        return room;
    }


    private ChatRoom createNewOneToOneChatRoom(Long userId1, Long userId2) {
        ChatRoom newRoom = new ChatRoom(false, Instant.now(), "1:1 채팅방");

        ChatParticipant participant1 = new ChatParticipant(newRoom, userId1);
        ChatParticipant participant2 = new ChatParticipant(newRoom, userId2);

        newRoom.addParticipant(participant1);
        newRoom.addParticipant(participant2);

        return chatRoomRepo.save(newRoom);
    }

    /**
     * 사용자가 채팅방을 나갑니다.
     * 1:1 채팅방의 경우, 상대방은 방에 남아있습니다.
     *
     * @param roomId 채팅방 ID
     * @param userId 나가려는 사용자 ID
     * @return 채팅방을 나가는 데 성공했는지 여부
     */
    @Transactional
    public void leaveRoom(Long roomId, Long userId) {
        ChatParticipant participant = chatParticipantRepository
                .findByChatRoomIdAndUserIdAndStatusIsNot(roomId, userId, ChatParticipantStatus.LEFT)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_PARTICIPANT_NOT_FOUND));

        ChatRoom chatRoom = participant.getChatRoom();

        if (chatRoom.getGroup() && chatRoom.getOwnerId().equals(userId)) {
            chatRoom.getParticipants().stream()
                    .filter(p -> !p.getUserId().equals(userId))
                    .filter(p -> p.getStatus() == ChatParticipantStatus.ACTIVE)
                    .min(Comparator.comparing(ChatParticipant::getJoinedAt))
                    .ifPresent(nextOwner -> chatRoom.changeOwner(nextOwner.getUserId()));
        }

        participant.leave();
        deleteRoomIfEmpty(roomId);
    }
    /**
     * 채팅방의 모든 참여자가 나갔는지 확인하고, 비어있으면 관련된 모든 데이터를 삭제합니다.
     *
     * @param roomId 확인할 채팅방 ID
     */
    @Transactional
    public void deleteRoomIfEmpty(Long roomId) {
        ChatRoom room = chatRoomRepo.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
        long remainingActiveParticipants = chatParticipantRepository.countByChatRoomIdAndStatus(roomId, ChatParticipantStatus.ACTIVE);
        if (remainingActiveParticipants == 0) {
            chatMessageRepo.deleteByChatRoomId(roomId);
            chatRoomRepo.delete(room);
        }
    }

    @Transactional(readOnly = true)
    public List<ChatRoomParticipantsResponse> getRoomParticipants(Long roomId) {
        ChatRoom chatRoom = chatRoomRepo.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
        List<ChatParticipant> participants = chatParticipantRepository.findByChatRoom(chatRoom);

        if (participants.isEmpty()) {
            return List.of();
        }
        List<Long> userIds = participants.stream()
                .map(ChatParticipant::getUserId)
                .distinct()
                .toList();
        Map<Long, UserResponseDto> userInfoMap = userClient.getUsersInfo(userIds).stream()
                .collect(Collectors.toMap(UserResponseDto::userId, Function.identity()));

        return participants.stream()
                .map(p -> {
                    UserResponseDto userInfo = userInfoMap.getOrDefault(p.getUserId(), UserResponseDto.unknown());

                    boolean isHost = chatRoom.getOwnerId() != null && chatRoom.getOwnerId().equals(p.getUserId());

                    return new ChatRoomParticipantsResponse(
                            userInfo.userId(),
                            userInfo.firstName(),
                            userInfo.lastName(),
                            userInfo.ImageUrl(),
                            isHost
                    );
                })
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ChatRoomSummaryResponse> searchRoomsByRoomName(Long userId, String roomNameKeyword) {
        List<ChatRoom> allActiveRooms = chatRoomRepo.findActiveChatRoomsByUserId(userId, ChatParticipantStatus.ACTIVE);
        if (allActiveRooms.isEmpty()) {
            return List.of();
        }

        List<Long> opponentUserIds = new ArrayList<>();
        List<Long> groupRoomIds = new ArrayList<>();
        Map<Long, Long> roomToOpponentIdMap = new HashMap<>();

        for (ChatRoom room : allActiveRooms) {
            if (room.getGroup()) {
                groupRoomIds.add(room.getId());
            } else {
                room.getParticipants().stream()
                        .map(ChatParticipant::getUserId)
                        .filter(participantId -> !participantId.equals(userId))
                        .findFirst()
                        .ifPresent(opponentId -> {
                            opponentUserIds.add(opponentId);
                            roomToOpponentIdMap.put(room.getId(), opponentId);
                        });
            }
        }


        Map<Long, UserResponseDto> opponentInfoMap = userClient.getUsersInfo(opponentUserIds).stream()
                .collect(Collectors.toMap(UserResponseDto::userId, Function.identity()));
        Map<Long, String> groupRoomImageMap = userClient.getImagesForChatRooms(groupRoomIds).stream()
                .collect(Collectors.toMap(ImageDto::relatedId, ImageDto::imageUrl));

        return allActiveRooms.stream()
                .map(room -> {
                    String lastMessageContent = chatMessageService.getLastMessageContent(room.getId());
                    Instant lastMessageTime = chatMessageService.getLastMessageTime(room.getId());
                    int unreadCount = chatMessageService.countUnreadMessages(room.getId(), userId);
                    String calculatedRoomName;
                    String roomImageUrl;

                    if (room.getGroup()) {
                        calculatedRoomName = room.getRoomName();
                        roomImageUrl = groupRoomImageMap.get(room.getId());
                    } else {
                        Long opponentId = roomToOpponentIdMap.get(room.getId());
                        UserResponseDto opponentInfo = opponentInfoMap.getOrDefault(opponentId, UserResponseDto.unknown());
                        calculatedRoomName = opponentInfo.firstName() + " " + opponentInfo.lastName();
                        roomImageUrl = opponentInfo.ImageUrl();
                    }

                    return new ChatRoomSummaryResponse(
                            room.getId(), calculatedRoomName, lastMessageContent, lastMessageTime,
                            roomImageUrl, unreadCount, room.getParticipants().size()
                    );
                })
                .filter(summary -> summary.roomName() != null &&
                        summary.roomName().toLowerCase().contains(roomNameKeyword.toLowerCase()))
                .sorted(Comparator.comparing(ChatRoomSummaryResponse::lastMessageTime, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    @Transactional
    public void toggleTranslation(Long roomId, Long userId, boolean enable) {
        ChatParticipant participant = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_CHAT_PARTICIPANT));
        participant.toggleTranslation(enable);
    }

    public ChatRoom getChatRoomById(Long roomId) {
        return chatRoomRepository.findByIdWithParticipants(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
    }
}