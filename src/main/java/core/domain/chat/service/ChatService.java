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
import core.global.image.entity.Image;
import core.global.image.repository.ImageRepository;
import core.global.image.service.ImageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatService {

    private final ChatRoomRepository chatRoomRepo;
    private final ChatParticipantRepository participantRepo;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatParticipantRepository chatParticipantRepository;
    private static final int MESSAGE_PAGE_SIZE = 20;
    private final TranslationService translationService;
    private final ImageRepository imageRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final SimpMessagingTemplate messagingTemplate; // 주입 필요
    private final ImageService imageService;
    private final UserClient userClient;


    public List<ChatRoomSummaryResponse> getMyAllChatRoomSummaries(Long userId) {
        List<ChatRoom> rooms = chatRoomRepo.findActiveChatRoomsWithParticipantsByUserId(userId, ChatParticipantStatus.ACTIVE);
        if (rooms.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> roomIds = rooms.stream().map(ChatRoom::getId).toList();

        Map<Long, ChatMessage> lastMessagesMap = findLatestMessageForRooms(roomIds);
        Map<Long, Instant> lastReadAtMap = rooms.stream()
                .flatMap(room -> room.getParticipants().stream())
                .filter(p -> p.getUserId().equals(userId) && p.getLastReadAt() != null)
                .collect(Collectors.toMap(p -> p.getChatRoom().getId(), ChatParticipant::getLastReadAt, (t1, t2) -> t1));
        Map<Long, Long> unreadCountsMap = countUnreadMessagesGroupedByRoomId(lastReadAtMap);

        Set<Long> opponentUserIds = rooms.stream()
                .filter(room -> !room.getGroup())
                .flatMap(room -> room.getParticipants().stream())
                .map(ChatParticipant::getUserId)
                .filter(id -> !id.equals(userId))
                .collect(Collectors.toSet());
        Map<Long, UserResponseDto> userInfoMap;

        if (!opponentUserIds.isEmpty()) {
            userInfoMap = userClient.getUsersInfo(new ArrayList<>(opponentUserIds)).stream()
                    .collect(Collectors.toMap(UserResponseDto::userId, dto -> dto));
        } else {
            userInfoMap = new HashMap<>();
        }

        List<Long> groupRoomIds = rooms.stream()
                .filter(ChatRoom::getGroup)
                .map(ChatRoom::getId)
                .toList();

        Map<Long, String> groupChatImagesMap;
        if (!groupRoomIds.isEmpty()) {
            groupChatImagesMap = userClient.getImagesForChatRooms(groupRoomIds).stream()
                    .collect(Collectors.toMap(ImageDto::relatedId, ImageDto::imageUrl));
        } else {
            groupChatImagesMap = new HashMap<>();
        }


        List<ChatRoomSummaryResponse> summaries = rooms.stream().map(room -> {
                    Long roomId = room.getId();
                    ChatMessage lastMessage = lastMessagesMap.get(roomId);
                    String lastMessageContent = (lastMessage != null) ? lastMessage.getContent() : "아직 메시지가 없습니다.";
                    Instant lastMessageTime = (lastMessage != null) ? lastMessage.getSentAt() : room.getCreatedAt();
                    int unreadCount = unreadCountsMap.getOrDefault(roomId, 0L).intValue();
                    int participantCount = room.getParticipants().size();
                    String roomName;
                    String roomImageUrl = null;

                    if (!room.getGroup()) {
                        Long opponentId = room.getParticipants().stream()
                                .map(ChatParticipant::getUserId)
                                .filter(id -> !id.equals(userId))
                                .findFirst()
                                .orElse(null);
                        UserResponseDto opponent = userInfoMap.get(opponentId);
                        if (opponent != null) {
                            roomName = opponent.firstName() + " " + opponent.lastName();
                            roomImageUrl = opponent.ImageUrl();
                        } else {
                            roomName = "(알 수 없는 사용자)";
                        }
                    } else {
                        roomName = room.getRoomName();
                        roomImageUrl = groupChatImagesMap.get(room.getId());
                    }

                    return new ChatRoomSummaryResponse(
                            roomId, roomName, lastMessageContent, lastMessageTime,
                            roomImageUrl, unreadCount, participantCount
                    );
                }).sorted(Comparator.comparing(ChatRoomSummaryResponse::lastMessageTime, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        return summaries;
    }

    /**
     * 🥫 식료품 창고(Repository)에 가서 마지막 메시지들을 가져온 후,
     * 메인 셰프가 쓰기 편하게 Map 그릇에 담아 반환합니다.
     */
    private Map<Long, ChatMessage> findLatestMessageForRooms(List<Long> roomIds) {
        List<ChatMessage> latestMessages = chatMessageRepository.findLatestMessagesForRooms(roomIds);

        return latestMessages.stream()
                .collect(Collectors.toMap(ChatMessage::getChatRoomId, message -> message, (m1, m2) -> m1));
    }

    /**
     * 🥫 식료품 창고(Repository)에 가서 안 읽은 메시지 수를 계산해 온 후,
     * 메인 셰프가 쓰기 편하게 Map 그릇에 담아 반환합니다.
     */
    private Map<Long, Long> countUnreadMessagesGroupedByRoomId(Map<Long, Instant> lastReadAtMap) {
        if (lastReadAtMap.isEmpty()) {
            return Collections.emptyMap();
        }

        List<Map<String, Object>> conditions = lastReadAtMap.entrySet().stream()
                .map(entry -> Map.<String, Object>of(
                        "room_id", entry.getKey(),
                        "sent_at", Map.of("$gt", entry.getValue())
                ))
                .toList();

        List<UnreadCountDto> unreadCounts = chatMessageRepository.countUnreadMessagesGroupedByRoomId(conditions);


        return unreadCounts.stream()
                .collect(Collectors.toMap(UnreadCountDto::roomId, UnreadCountDto::unreadCount));
    }


    @Transactional
    public ChatRoom findOrCreateOneToOneRoom(Long currentUserId, Long otherUserId) {

        return chatRoomRepo.findOneToOneRoomByParticipantIds(currentUserId, otherUserId)
                .map(room -> {
                    participantRepo.findByChatRoomAndUserId(room, currentUserId)
                            .filter(p -> p.getStatus() == ChatParticipantStatus.LEFT)
                            .ifPresent(ChatParticipant::reJoin);
                    return room;
                })
                .orElseGet(() -> createNewOneToOneRoom(currentUserId, otherUserId));
    }

    private ChatRoom createNewOneToOneRoom(Long userId1, Long userId2) {

        ChatRoom newRoom = new ChatRoom(false, Instant.now());

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


    @Transactional(readOnly = true)
    public List<ChatRoomParticipantsResponse> getRoomParticipants(Long roomId) {
        ChatRoom chatRoom = chatRoomRepo.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
        Long ownerId = chatRoom.getOwnerId();

        List<ChatParticipant> participants = participantRepo.findByChatRoomId(roomId);
        if (participants.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> userIds = participants.stream()
                .map(ChatParticipant::getUserId)
                .toList();

        Map<Long, UserResponseDto> userInfoMap = userClient.getUsersInfo(userIds).stream()
                .collect(Collectors.toMap(UserResponseDto::userId, dto -> dto));

        return participants.stream()
                .map(p -> {
                    UserResponseDto userInfo = userInfoMap.get(p.getUserId());
                    if (userInfo == null) {
                        // main-service에서 정보를 가져오지 못한 경우 (탈퇴한 사용자 등)
                        return new ChatRoomParticipantsResponse(p.getUserId(), "(알 수 없음)", "", null, false);
                    }

                    boolean isHost = ownerId != null && ownerId.equals(p.getUserId());

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
    /**
     * @apiNote 채팅방 메시지를 무한 스크롤로 조회하는 핵심 로직입니다.
     * 이 메서드는 항상 ChatMessage Document 목록을 반환합니다.
     *
     * @param roomId 채팅방 ID
     * @param lastMessageId 마지막으로 조회된 메시지 ID (무한 스크롤용)
     * @return ChatMessage Document 목록
     */
    @Transactional(readOnly = true)
    public List<ChatMessage> getRawMessages(Long roomId, String lastMessageId) { // lastMessageId 타입 변경

        // RDBMS의 ChatParticipant 테이블은 그대로 사용한다고 가정
        // 메인 서비스와 채팅 서비스가 분리되어 있다면, 이 로직은 API 호출로 대체될 수 있음
        ChatParticipant participant = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_PARTICIPANT_NOT_FOUND));

        // MongoDB의 Pageable 객체
        Pageable pageable = PageRequest.of(0, MESSAGE_PAGE_SIZE, Sort.by("sentAt").descending());

        if (participant.getStatus() == ChatParticipantStatus.LEFT && participant.getLastLeftAt() != null) {
            Instant lastLeftAt = participant.getLastLeftAt();

            // MongoDB는 sentAt과 id를 동시에 사용한 쿼리가 RDBMS와 다름
            // lastMessageId가 있으면, 해당 메시지보다 오래된 메시지들을 찾음
            if (lastMessageId != null) {
                // lastMessageId의 sentAt을 먼저 조회하여 sentAtBefore 쿼리에 사용
                Instant lastMessageSentAt = chatMessageRepository.findById(lastMessageId)
                        .map(ChatMessage::getSentAt)
                        .orElse(null);

                // lastMessageSentAt보다 오래된 메시지 중 lastLeftAt 이후 메시지 조회
                return chatMessageRepository.findByChatRoomIdAndSentAtAfterAndSentAtBefore(
                        roomId, lastLeftAt, lastMessageSentAt, pageable
                );
            } else {
                // 채팅방을 나간 후의 메시지 조회
                return chatMessageRepository.findByChatRoomIdAndSentAtAfter(
                        roomId, lastLeftAt, pageable
                );
            }
        } else { // 채팅방에 참여 중인 상태
            if (lastMessageId != null) {
                // lastMessageId의 sentAt을 먼저 조회하여 sentAtBefore 쿼리에 사용
                Instant lastMessageSentAt = chatMessageRepository.findById(lastMessageId)
                        .map(ChatMessage::getSentAt)
                        .orElse(null);

                // lastMessageSentAt보다 오래된 메시지 조회
                return chatMessageRepository.findByChatRoomIdAndSentAtBefore(
                        roomId, lastMessageSentAt, pageable
                );
            } else {
                // 가장 최신 메시지 조회
                return chatMessageRepository.findByChatRoomId(
                        roomId, pageable
                );
            }
        }
    }
    /**
     * @apiNote 채팅방 메시지를 조회하고, 번역 요청에 따라 ChatMessageResponse 목록을 반환합니다.
     * 이 메서드가 컨트롤러에서 호출되는 주된 엔드포인트가 됩니다.
     *
     * @param roomId 채팅방 ID
     * @param userId 조회하는 사용자 ID
     * @param lastMessageId 마지막으로 조회된 메시지 ID (무한 스크롤용)
     * @return ChatMessageResponse 목록
     */


    @Transactional(readOnly = true)
    public List<ChatMessageResponse> getMessages(
            Long roomId,
            Long userId,
            Long lastMessageId

    ) {
        ChatParticipant participant = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_CHAT_PARTICIPANT));

        boolean needsTranslation = participant.isTranslateEnabled();
        String targetLanguage = participant.getUser().getTranslateLanguage();

        List<ChatMessage> messages = getRawMessages(roomId, userId, lastMessageId);

        if (needsTranslation && targetLanguage != null && !targetLanguage.isEmpty()) {
            List<String> originalContents = messages.stream()
                    .map(ChatMessage::getContent)
                    .collect(Collectors.toList());
            List<String> translatedContents = translationService.translateMessages(originalContents, targetLanguage);

            return IntStream.range(0, messages.size())
                    .mapToObj(i -> {
                        ChatMessage message = messages.get(i);
                        String translatedContent = translatedContents.get(i);
                        User sender = message.getSender();
                        String senderImageUrl = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.USER, sender.getId())
                                .map(Image::getUrl)
                                .orElse(null);

                        return new ChatMessageResponse(
                                message.getId(),
                                message.getChatRoom().getId(),
                                sender.getId(),
                                message.getContent(),
                                translatedContent,
                                message.getSentAt(),
                                sender.getFirstName(),
                                sender.getLastName(),
                                senderImageUrl
                        );
                    }).collect(Collectors.toList());
        }

        else {
            return messages.stream()
                    .map(message -> {
                        User sender = message.getSender();
                        String senderImageUrl = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.USER, sender.getId())
                                .map(Image::getUrl)
                                .orElse(null);

                        return new ChatMessageResponse(
                                message.getId(),
                                message.getChatRoom().getId(),
                                sender.getId(),
                                message.getContent(),
                                null,
                                message.getSentAt(),
                                sender.getFirstName(),
                                sender.getLastName(),
                                senderImageUrl
                        );
                    }).collect(Collectors.toList());
        }
    }

    @Transactional
    public ChatMessage saveMessage(Long roomId, Long senderId, String content) {
        User sender = userRepository.findById(senderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        ChatRoom room = chatRoomRepo.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));

        ChatParticipant senderParticipant = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, senderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_PARTICIPANT_NOT_FOUND));

        if (senderParticipant.getStatus() == ChatParticipantStatus.LEFT) {
            senderParticipant.reJoin();
        }

        if (Boolean.FALSE.equals(room.getGroup())) {
            List<ChatParticipant> participants = chatParticipantRepository.findByChatRoomId(roomId);
            for (ChatParticipant participant : participants) {
                if (!participant.getUser().getId().equals(senderId) && participant.getStatus() == ChatParticipantStatus.LEFT) {
                    participant.reJoin();
                }
            }
        }

        ChatMessage message = new ChatMessage(room, sender, content);
        return chatMessageRepository.save(message);
    }

    // ChatService.java

    @Transactional(readOnly = true)
    public List<ChatMessageResponse> searchMessages(Long roomId, Long userId, String keyword) {
        ChatParticipant participant = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_CHAT_PARTICIPANT));

        boolean needsTranslation = participant.isTranslateEnabled();
        String targetLanguage = participant.getUser().getTranslateLanguage();

        if (!needsTranslation || targetLanguage == null || targetLanguage.isEmpty()) {
            List<ChatMessage> messages = chatMessageRepository.findByChatRoomIdAndContentContaining(roomId, keyword);
            return messages.stream()
                    .map(message -> {
                        User sender = message.getSender();
                        String userImageUrl = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.USER, sender.getId())
                                .map(Image::getUrl)
                                .orElse(null);

                        return new ChatMessageResponse(
                                message.getId(),
                                message.getChatRoom().getId(),
                                sender.getId(),
                                message.getContent(),
                                null,
                                message.getSentAt(),
                                sender.getFirstName(),
                                sender.getLastName(),
                                userImageUrl
                        );
                    })
                    .collect(Collectors.toList());
        }
        else {
            List<ChatMessage> allMessages = chatMessageRepository.findByChatRoomIdOrderByIdAsc(roomId);
            if (allMessages.isEmpty()) {
                return new ArrayList<>();
            }

            List<String> originalContents = allMessages.stream().map(ChatMessage::getContent).collect(Collectors.toList());
            List<String> translatedContents = translationService.translateMessages(originalContents, targetLanguage);

            List<MessagePair> messagePairs = IntStream.range(0, allMessages.size())
                    .mapToObj(i -> new MessagePair(allMessages.get(i), translatedContents.get(i)))
                    .toList();

            return messagePairs.stream()
                    .filter(pair -> pair.translatedContent().toLowerCase().contains(keyword.toLowerCase()))
                    .map(pair -> {
                        User sender = pair.originalMessage().getSender();
                        String userImageUrl = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.USER, sender.getId())
                                .map(Image::getUrl)
                                .orElse(null);

                        return new ChatMessageResponse(
                                pair.originalMessage().getId(),
                                pair.originalMessage().getChatRoom().getId(),
                                sender.getId(),
                                pair.originalMessage().getContent(),
                                pair.translatedContent(),
                                pair.originalMessage().getSentAt(),
                                sender.getFirstName(),
                                sender.getLastName(),
                                userImageUrl
                        );
                    })
                    .collect(Collectors.toList());
        }
    }

    private record MessagePair(ChatMessage originalMessage, String translatedContent) {}
    /**
     * @apiNote 메시지 읽음 상태를 업데이트합니다.
     * 그룹 채팅에서 '누가 읽었는지'를 관리하는 로직입니다.
     *
     * @param roomId 메시지를 읽은 채팅방 ID
     * @param readerId 메시지를 읽은 사용자 ID
     * @param lastReadMessageId 마지막으로 읽은 메시지 ID
     */
    @Transactional
    public void markMessagesAsRead(Long roomId, Long readerId, Long lastReadMessageId) {
        ChatParticipant readerParticipant = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, readerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_PARTICIPANT_NOT_FOUND));
        readerParticipant.setLastReadMessageId(lastReadMessageId);
    }

    public String getLastMessageContent(Long roomId) {
        return chatMessageRepository.findTopByChatRoomIdOrderBySentAtDesc(roomId) // Optional<ChatMessage> 반환
                .map(ChatMessage::getContent)
                .orElse(null);
    }

    public Instant getLastMessageTime(Long roomId) {
        return chatMessageRepository.findTopByChatRoomIdOrderBySentAtDesc(roomId)
                .map(ChatMessage::getSentAt)
                .orElse(null);
    }
    public ChatRoom getChatRoomById(Long roomId) {
        return chatRoomRepository.findByIdWithParticipantsAndUsers(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
    }

    public GroupChatDetailResponse getGroupChatDetails(Long chatRoomId) {
        ChatRoom chatRoom = chatRoomRepository.findById(chatRoomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));

        String roomImageUrl = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(
                ImageType.CHAT_ROOM, chatRoom.getId()
        ).map(Image::getUrl).orElse(null);

        Long ownerId = chatRoom.getOwner().getId();
        String ownerImageUrl = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(
                ImageType.USER,
                ownerId
        ).map(Image::getUrl).orElse(null);

        List<String> otherParticipantsImageUrls = chatRoom.getParticipants().stream()
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
                chatRoom.getParticipants().size(),
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

    public int countUnreadMessages(Long roomId, Long userId) {
        Long lastReadId = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
                .map(ChatParticipant::getLastReadMessageId)
                .orElse(0L);

        return chatMessageRepository.countUnreadMessages(roomId, lastReadId, userId);
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
    @Transactional
    public List<ChatMessageFirstResponse> getFirstMessages(Long roomId, Long userId) {
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("채팅방을 찾을 수 없습니다."));

        chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new IllegalArgumentException("해당 채팅방에 참여하지 않은 사용자입니다."));
        List<ChatMessage> messages = chatMessageRepository.findTop50ByChatRoomIdOrderBySentAtDesc(roomId);

        return messages.stream()
                .map(message -> ChatMessageFirstResponse.fromEntity(message, chatRoom, imageRepository))
                .collect(Collectors.toList());
    }


    /**
     * 사용자 프로필 조회 서비스 메서드
     * @param userId 조회할 사용자의 ID
     * @return UserProfileResponse DTO
     */
    @Transactional(readOnly = true)
    public ChatUserProfileResponse getUserProfile(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        List<Image> images = imageRepository.findByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.USER, userId);

        String imageUrl = images.stream()
                .findFirst()
                .map(Image::getUrl)
                .orElse(null);

        return ChatUserProfileResponse.from(user, imageUrl);
    }
    @Transactional
    public void toggleTranslation(Long roomId, Long userId, boolean enable) {
        ChatParticipant participant = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_CHAT_PARTICIPANT));
        participant.toggleTranslation(enable);
    }


    @Transactional
    public void processAndSendChatMessage(SendMessageRequest req) {
        ChatMessage savedMessage = this.saveMessage(req.roomId(), req.senderId(), req.content());
        String originalContent = savedMessage.getContent();


        ChatRoom chatRoom = savedMessage.getChatRoom();
        List<ChatParticipant> participants = chatRoom.getParticipants();
        User senderUser = userRepository.findById(req.senderId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        String userImageUrl = imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.USER, req.senderId())
                .map(Image::getUrl)
                .orElse(null);

        for (ChatParticipant participant : participants) {
            User recipient = participant.getUser();
            String targetContent = null;

            if (participant.isTranslateEnabled()) {
                String targetLanguage = recipient.getTranslateLanguage();
                if (targetLanguage != null && !targetLanguage.isEmpty()) {
                    List<String> translatedList = translationService.translateMessages(List.of(originalContent), targetLanguage);
                    if (!translatedList.isEmpty()) {
                        targetContent = translatedList.getFirst();
                    }
                }
            }

            ChatMessageResponse messageResponse = new ChatMessageResponse(
                    savedMessage.getId(),
                    chatRoom.getId(),
                    savedMessage.getSender().getId(),
                    originalContent,
                    targetContent,
                    savedMessage.getSentAt(),
                    senderUser.getFirstName(),
                    senderUser.getLastName(),
                    userImageUrl
            );
            messagingTemplate.convertAndSend("/topic/user/" + recipient.getId() + "/messages", messageResponse);

            int unreadCount = this.countUnreadMessages(req.roomId(), recipient.getId());
            ChatRoomSummaryResponse summary = ChatRoomSummaryResponse.from(
                    chatRoom,
                    recipient.getId(),
                    originalContent,
                    savedMessage.getSentAt(),
                    unreadCount,
                    imageRepository
            );
            messagingTemplate.convertAndSend("/topic/user/" + recipient.getId() + "/rooms", summary);
        }
    }
    @Transactional
    public void markAllMessagesAsReadInRoom(Long roomId, Long readerId) {
        Optional<ChatMessage> lastMessageOpt = chatMessageRepository.findTopByChatRoomIdOrderByIdDesc(roomId);

        if (lastMessageOpt.isPresent()) {
            ChatMessage lastMessage = lastMessageOpt.get();
            Long lastMessageId = lastMessage.getId();
            AllmarkMessagesAsRead(roomId, readerId, lastMessageId);

            log.info(">>>> All messages marked as read for userId: {} in roomId: {}", readerId, roomId);
        } else {
            log.info(">>>> No messages to mark as read in roomId: {}", roomId);
        }
    }

    /**
     * 특정 메시지 ID까지 읽음 처리하는 기존 메서드 (이전 답변의 효율적인 버전)
     */
    @Transactional
    public void AllmarkMessagesAsRead(Long roomId, Long readerId, Long lastReadMessageId) {
        ChatParticipant readerParticipant = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, readerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_PARTICIPANT_NOT_FOUND));

        readerParticipant.setLastReadMessageId(lastReadMessageId);
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

}
