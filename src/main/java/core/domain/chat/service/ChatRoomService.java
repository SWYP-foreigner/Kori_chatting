package core.domain.chat.service;

import core.domain.chat.dto.ChatRoomParticipantsResponse;
import core.domain.chat.dto.ChatRoomSummaryResponse;
import core.domain.chat.dto.CreateGroupChatRequest;
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
import core.global.image.entity.Image;
import core.global.image.repository.ImageRepository;
import core.global.image.service.ImageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatRoomService {

    private final ChatRoomRepository chatRoomRepo;
    private final ChatParticipantRepository participantRepo;
    private final ChatMessageRepository chatMessageRepo;
    private final ImageRepository imageRepository;

    private final ChatMessageService chatMessageService;

    private record ChatRoomWithTime(ChatRoom room, Instant lastMessageTime) {}

    public List<ChatRoomSummaryResponse> getMyAllChatRoomSummaries(Long userId) {
        User currentUser = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        List<ChatRoom> rooms = chatRoomRepo.findActiveChatRoomsByUserId(userId, ChatParticipantStatus.ACTIVE);

        return rooms.stream()
                .map(room -> new ChatRoomWithTime(room, getLastMessageTime(room.getId())))
                .sorted(Comparator.comparing(
                        ChatRoomWithTime::lastMessageTime,
                        Comparator.nullsLast(Comparator.reverseOrder())
                ))
                .map(roomWithTime -> {
                    ChatRoom room = roomWithTime.room();
                    Instant lastMessageTime = roomWithTime.lastMessageTime();

                    String lastMessageContent = getLastMessageContent(room.getId());
                    int unreadCount = countUnreadMessages(room.getId(), userId);
                    int participantCount = room.getParticipants().size();
                    String roomName;
                    String roomImageUrl;

                    if (!room.getGroup()) {
                        Optional<User> opponentOpt = room.getParticipants().stream()
                                .map(ChatParticipant::getUser)
                                .filter(u -> !u.getId().equals(userId))
                                .findFirst();

                        if (opponentOpt.isPresent()) {
                            User opponent = opponentOpt.get();
                            roomName = opponent.getFirstName()+" "+opponent.getLastName();
                            roomImageUrl = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.USER, opponent.getId())
                                    .map(Image::getUrl)
                                    .orElse(null);
                        } else {
                            roomName = "(알 수 없는 사용자)";
                            roomImageUrl = null;
                        }

                    } else {
                        roomName = room.getRoomName();
                        roomImageUrl = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.CHAT_ROOM, room.getId())
                                .map(Image::getUrl)
                                .orElse(null);
                    }


                    return new ChatRoomSummaryResponse(
                            room.getId(),
                            roomName,
                            lastMessageContent,
                            lastMessageTime,
                            roomImageUrl,
                            unreadCount,
                            participantCount
                    );
                })
                .toList();
    }

    @Transactional
    public ChatRoom createRoom(Long currentUserId, Long otherUserId) {
        List<Long> userIds = Arrays.asList(currentUserId, otherUserId);
        List<ChatRoom> existingRooms = chatRoomRepo.findOneToOneRoomByParticipantIds(userIds);

        if (!existingRooms.isEmpty()) {
            if (existingRooms.size() > 1) {
                log.warn("중복된 1:1 채팅방 발견. 사용자 ID: {}, {}. 첫 번째 방을 사용합니다.", currentUserId, otherUserId);
            }
            ChatRoom room = existingRooms.get(0);
            return handleExistingRoom(room, currentUserId);
        } else {
            return createNewOneToOneChatRoom(currentUserId, otherUserId);
        }
    }

    @Transactional
    public void createGroupChatRoom(Long userId, CreateGroupChatRequest request) {
        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        ChatRoom newRoom = new ChatRoom(
                true,
                Instant.now(),
                request.roomName(),
                request.description(),
                owner
        );
        ChatRoom savedRoom = chatRoomRepository.save(newRoom);

        ChatParticipant ownerParticipant = new ChatParticipant(savedRoom, owner);
        chatParticipantRepository.save(ownerParticipant);

        if (request.roomImageUrl() != null && !request.roomImageUrl().isBlank()) {
            imageService.upsertChatRoomProfileImage(savedRoom.getId(), request.roomImageUrl());
        }

    }

    private ChatRoom handleExistingRoom(ChatRoom room, Long currentUserId) {

        Optional<ChatParticipant> currentParticipant = room.getParticipants().stream()
                .filter(p -> p.getUser().getId().equals(currentUserId))
                .findFirst();
        if (currentParticipant.isPresent() && currentParticipant.get().getStatus() == ChatParticipantStatus.LEFT) {
            currentParticipant.get().reJoin();
        }
        return room;
    }


    private ChatRoom createNewOneToOneChatRoom(Long userId1, Long userId2) {
        User currentUser = userRepository.findById(userId1)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        User otherUser = userRepository.findById(userId2)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        ChatRoom newRoom = new ChatRoom(false, Instant.now(), "1:1 채팅방");

        ChatParticipant participant1 = new ChatParticipant(newRoom, currentUser);
        ChatParticipant participant2 = new ChatParticipant(newRoom, otherUser);

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
    public boolean leaveRoom(Long roomId, Long userId) {
        ChatParticipant participant = participantRepo.findByChatRoomIdAndUserIdAndStatusIsNot(roomId, userId, ChatParticipantStatus.LEFT)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_PARTICIPANT_NOT_FOUND));
        participant.leave();
        deleteRoomIfEmpty(roomId);
        return true;
    }

    /**
     * 채팅방의 모든 참여자가 나갔는지 확인하고, 비어있으면 삭제합니다.
     * 이 메서드는 leaveRoom()에서 호출되어 채팅방 삭제 로직을 분리합니다.
     *
     * @param roomId 확인할 채팅방 ID
     */
    @Transactional
    public void deleteRoomIfEmpty(Long roomId) {
        ChatRoom room = chatRoomRepo.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));

        long remainingActiveParticipants = participantRepo.countByChatRoomIdAndStatus(roomId, ChatParticipantStatus.ACTIVE);

        if (remainingActiveParticipants == 0) {
            chatMessageRepository.deleteByChatRoomId(roomId);
            chatRoomRepo.delete(room);
        }
    }
    public List<ChatRoomParticipantsResponse> getRoomParticipants(Long roomId) {
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));

        List<ChatParticipant> participants = chatParticipantRepository.findByChatRoom(chatRoom);
        return participants.stream()
                .map(p -> {
                    String userImageUrl = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(
                                    ImageType.USER, p.getUser().getId())
                            .map(Image::getUrl)
                            .orElse(null);

                    boolean isHost = chatRoom.getOwner() != null && chatRoom.getOwner().getId().equals(p.getUser().getId());

                    return new ChatRoomParticipantsResponse(
                            p.getUser().getId(),
                            p.getUser().getFirstName(),
                            p.getUser().getLastName(),
                            userImageUrl,
                            isHost
                    );
                })
                .collect(Collectors.toList());
    }

    public List<ChatRoomSummaryResponse> searchRoomsByRoomName(Long userId, String roomName) {
        List<ChatRoom> rooms = chatParticipantRepository.findChatRoomsByUserIdAndRoomName(userId, roomName);

        return rooms.stream()
                .map(room -> createChatRoomSummary(room, userId))
                .collect(Collectors.toList());
    }

    private ChatRoomSummaryResponse createChatRoomSummary(ChatRoom room, Long userId) {
        Optional<ChatMessage> lastMessageOpt = chatMessageRepository.findFirstByChatRoomIdOrderBySentAtDesc(room.getId());

        String lastMessageContent = lastMessageOpt.map(ChatMessage::getContent).orElse(null);
        Instant lastMessageTime = lastMessageOpt.map(ChatMessage::getSentAt).orElse(null);

        int unreadCount = countUnreadMessages(room.getId(), userId);

        return ChatRoomSummaryResponse.from(
                room,
                userId,
                lastMessageContent,
                lastMessageTime,
                unreadCount,
                imageRepository
        );
    }
    @Transactional
    public void toggleTranslation(Long roomId, Long userId, boolean enable) {
        ChatParticipant participant = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_CHAT_PARTICIPANT));
        participant.toggleTranslation(enable);
    }

    public ChatRoom getChatRoomById(Long roomId) {
        return chatRoomRepository.findByIdWithParticipantsAndUsers(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
    }
}