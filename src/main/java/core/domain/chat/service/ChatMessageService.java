package core.domain.chat.service;


import core.domain.chat.client.UserClient;
import core.domain.chat.dto.*;
import core.domain.chat.entity.ChatMessage;
import core.domain.chat.entity.ChatParticipant;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.chat.repository.ChatParticipantRepository;
import core.domain.chat.repository.ChatRoomRepository;
import core.domain.notification.dto.NotificationEvent;
import core.domain.user.entity.User;
import core.global.enums.ChatParticipantStatus;
import core.global.enums.ErrorCode;
import core.global.enums.ImageType;
import core.global.enums.NotificationType;
import core.global.exception.BusinessException;
import core.global.service.TranslationService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatMessageService {

    private final ChatMessageRepository chatMessageRepository;
    private final ChatParticipantRepository chatParticipantRepository;
    private final ChatRoomRepository chatRoomRepo;
    private final TranslationService translationService;
    private final SimpMessagingTemplate messagingTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final UserClient userClient;

    private static final int MESSAGE_PAGE_SIZE = 20;
    private final ChatRoomService chatRoomService;

    private record MessagePair(ChatMessage originalMessage, String translatedContent) {}

        /**
         * @apiNote [최종 리팩토링] 채팅방 메시지를 조회하고, Bulk API를 통해 유저 정보를 효율적으로 결합하여 반환합니다.
         *
         * @param roomId 채팅방 ID
         * @param userId 조회하는 사용자 ID
         * @param lastMessageId 마지막으로 조회된 메시지 ID (무한 스크롤용)
         * @return ChatMessageResponse 목록
         */
        public List<ChatMessageResponse> getMessages(Long roomId, Long userId, String lastMessageId) {

            ChatParticipant participant = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_CHAT_PARTICIPANT));

            UserResponseDto currentUserInfo = userClient.getUserProfile(userId);
            boolean needsTranslation = participant.isTranslateEnabled();
            String targetLanguage = currentUserInfo.translateLanguage();

            List<ChatMessage> messages = getRawMessages(roomId, userId, lastMessageId);
            if (messages.isEmpty()) {
                return List.of();
            }

            List<Long> senderIds = messages.stream()
                    .map(ChatMessage::getSenderId)
                    .distinct()
                    .toList();

            Map<Long, UserResponseDto> senderInfoMap = userClient.getUsersInfo(senderIds).stream()
                    .collect(Collectors.toMap(UserResponseDto::userId, Function.identity()));


            if (needsTranslation && targetLanguage != null && !targetLanguage.isEmpty()) {
                List<String> originalContents = messages.stream().map(ChatMessage::getContent).toList();
                List<String> translatedContents = translationService.translateMessages(originalContents, targetLanguage);

                return IntStream.range(0, messages.size()).mapToObj(i -> {
                    ChatMessage message = messages.get(i);
                    UserResponseDto sender = senderInfoMap.getOrDefault(message.getSenderId(), UserResponseDto.unknown());

                    return new ChatMessageResponse(
                            message.getId(),
                            message.getChatRoomId(),
                            sender.userId(),
                            message.getContent(),
                            translatedContents.get(i),
                            message.getSentAt(),
                            sender.firstName(),
                            sender.lastName(),
                            sender.ImageUrl()
                    );
                }).toList();
            } else {
                return messages.stream().map(message -> {
                    UserResponseDto sender = senderInfoMap.getOrDefault(message.getSenderId(), UserResponseDto.unknown());

                    return new ChatMessageResponse(
                            message.getId(),
                            message.getChatRoomId(),
                            sender.userId(),
                            message.getContent(),
                            null,
                            message.getSentAt(),
                            sender.firstName(),
                            sender.lastName(),
                            sender.ImageUrl()
                    );
                }).toList();
            }
        }

    /**
     * @apiNote [리팩토링 후] 채팅방 메시지를 무한 스크롤로 조회하는 핵심 로직입니다.
     * MongoDB 리포지토리를 사용하도록 수정되었습니다.
     *
     * @param roomId 채팅방 ID
     * @param userId 조회하는 사용자 ID
     * @param lastMessageId 마지막으로 조회된 메시지 ID (MongoDB _id 이므로 String 타입)
     * @return ChatMessage 엔티티 목록
     */
    @Transactional(readOnly = true)
    public List<ChatMessage> getRawMessages(Long roomId, Long userId, String lastMessageId) { // Long -> String
        ChatParticipant participant = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_PARTICIPANT_NOT_FOUND));

        Pageable pageable = PageRequest.of(0, MESSAGE_PAGE_SIZE, Sort.by("sentAt").descending());
        if (participant.getStatus() == ChatParticipantStatus.LEFT && participant.getLastLeftAt() != null) {
            Instant lastLeftAt = participant.getLastLeftAt();

            if (lastMessageId != null) {
                return chatMessageRepository.findByChatRoomIdAndSentAtAfterAndIdLessThan(
                        roomId, lastLeftAt, lastMessageId, pageable
                );
            } else {
                return chatMessageRepository.findByChatRoomIdAndSentAtAfter(
                        roomId, lastLeftAt, pageable
                );
            }
        } else {
            if (lastMessageId != null) {
                return chatMessageRepository.findByChatRoomIdAndIdLessThan(
                        roomId, lastMessageId, pageable
                );
            } else {
                return chatMessageRepository.findByChatRoomId(
                        roomId, pageable
                );
            }
        }
    }

    @Transactional(readOnly = true)
    public List<ChatMessageFirstResponse> getFirstMessages(Long roomId, Long userId) {
        chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
                .filter(participant -> participant.getStatus() != ChatParticipantStatus.LEFT)
                .orElseThrow(() -> new IllegalArgumentException("채팅방에 참여하지 않았거나 나간 사용자입니다."));

        List<ChatMessage> messages = chatMessageRepository.findTop50ByChatRoomIdOrderBySentAtDesc(roomId);
        if (messages.isEmpty()) {
            return List.of();
        }

        List<Long> senderIds = messages.stream()
                .map(ChatMessage::getSenderId)
                .distinct()
                .toList();

        Map<Long, UserResponseDto> senderInfoMap = userClient.getUsersInfo(senderIds).stream()
                .collect(Collectors.toMap(UserResponseDto::userId, Function.identity()));

        return messages.stream()
                .map(message -> {
                    UserResponseDto sender = senderInfoMap.getOrDefault(message.getSenderId(), UserResponseDto.unknown());
                    return ChatMessageFirstResponse.from(message, sender);
                })
                .collect(Collectors.toList());
    }


    @Transactional
    public ChatMessage saveMessage(Long roomId, Long senderId, String content) {
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
                if (!participant.getUserId().equals(senderId) && participant.getStatus() == ChatParticipantStatus.LEFT) {
                    participant.reJoin();
                }
            }
        }
        ChatMessage message = new ChatMessage(room.getId(), senderId, content);
        return chatMessageRepository.save(message);
    }

    @Transactional
    public void processAndSendChatMessage(SendMessageRequest req) {
        ChatMessage savedMessage = this.saveMessage(req.roomId(), req.senderId(), req.content());

        ChatRoom chatRoom = chatRoomRepo.findById(req.roomId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
        List<ChatParticipant> participants = chatRoom.getParticipants();
        if (participants.isEmpty()) {
            return;
        }


        List<Long> userIds = participants.stream().map(ChatParticipant::getUserId).distinct().toList();
        Map<Long, UserResponseDto> userInfoMap = userClient.getUsersInfo(userIds).stream()
                .collect(Collectors.toMap(UserResponseDto::userId, Function.identity()));

        String groupRoomImageUrl = null;
        if (chatRoom.getGroup()) {
            List<ImageDto> roomImages = userClient.getImagesForChatRooms(List.of(chatRoom.getId()));
            groupRoomImageUrl = roomImages.isEmpty() ? null : roomImages.getFirst().imageUrl();
        }

        participants.stream()
                .filter(p -> p.getUserId().equals(req.senderId()))
                .findFirst()
                .ifPresent(p -> p.updateLastReadMessageId(savedMessage.getId()));

        for (ChatParticipant participant : participants) {
            Long currentParticipantId = participant.getUserId();
            UserResponseDto recipientInfo = userInfoMap.getOrDefault(currentParticipantId, UserResponseDto.unknown());
            UserResponseDto senderInfo = userInfoMap.getOrDefault(req.senderId(), UserResponseDto.unknown());
            String targetContent = null;


            if (participant.isTranslateEnabled() && recipientInfo.translateLanguage() != null) {
                targetContent = translationService.translateMessages(List.of(savedMessage.getContent()), recipientInfo.translateLanguage()).getFirst();
            }

            if (!recipientInfo.userId().equals(req.senderId())) {
                String notificationMessage = senderInfo.firstName() + "님으로부터 새로운 메시지";
                NotificationEvent event = new NotificationEvent(
                        recipientInfo.userId(), NotificationType.chat,
                        notificationMessage, chatRoom.getId(), senderInfo.userId()
                );
                eventPublisher.publishEvent(event);
            }


            ChatMessageResponse messageResponse = new ChatMessageResponse(
                    savedMessage.getId(), chatRoom.getId(), senderInfo.userId(),
                    savedMessage.getContent(), targetContent, savedMessage.getSentAt(),
                    senderInfo.firstName(), senderInfo.lastName(), senderInfo.ImageUrl()
            );
            messagingTemplate.convertAndSend("/topic/user/" + recipientInfo.userId() + "/messages", messageResponse);

            String summaryRoomName;
            String summaryRoomImageUrl;

            if (chatRoom.getGroup()) {
                summaryRoomName = chatRoom.getRoomName();
                summaryRoomImageUrl = groupRoomImageUrl;
            } else {
                UserResponseDto otherUserInfo = participants.stream()
                        .filter(p -> !p.getUserId().equals(currentParticipantId))
                        .findFirst()
                        .map(other -> userInfoMap.get(other.getUserId()))
                        .orElse(senderInfo);

                summaryRoomName = otherUserInfo.firstName() + " " + otherUserInfo.lastName();
                summaryRoomImageUrl = otherUserInfo.ImageUrl();
            }

            int unreadCount = this.countUnreadMessages(req.roomId(), recipientInfo.userId());
            ChatRoomSummaryResponse summary = ChatRoomSummaryResponse.from(
                    chatRoom,
                    savedMessage.getContent(),
                    savedMessage.getSentAt(),
                    unreadCount,
                    summaryRoomName,
                    summaryRoomImageUrl
            );
            messagingTemplate.convertAndSend("/topic/user/" + recipientInfo.userId() + "/rooms", summary);
        }
    }

    @Transactional(readOnly = true)
    public List<ChatMessageResponse> searchMessages(Long roomId, Long userId, String keyword) {
        ChatParticipant participant = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_CHAT_PARTICIPANT));

        boolean needsTranslation = participant.isTranslateEnabled();

        if (!needsTranslation) {
            List<ChatMessage> messages = chatMessageRepository.findByChatRoomIdAndContentContainingIgnoreCase(roomId, keyword);
            if (messages.isEmpty()) {
                return List.of();
            }

            List<Long> senderIds = messages.stream().map(ChatMessage::getSenderId).distinct().toList();
            Map<Long, UserResponseDto> senderInfoMap = userClient.getUsersInfo(senderIds).stream()
                    .collect(Collectors.toMap(UserResponseDto::userId, Function.identity()));

            return messages.stream()
                    .map(message -> {
                        UserResponseDto sender = senderInfoMap.getOrDefault(message.getSenderId(), UserResponseDto.unknown());
                        return new ChatMessageResponse(
                                message.getId(), message.getChatRoomId(), sender.userId(),
                                message.getContent(), null, message.getSentAt(),
                                sender.firstName(), sender.lastName(), sender.ImageUrl()
                        );
                    })
                    .sorted(Comparator.comparing(ChatMessageResponse::sentAt).reversed())
                    .toList();
        }
        else {
            UserResponseDto currentUserInfo = userClient.getUserProfile(userId);
            String targetLanguage = currentUserInfo.translateLanguage();

            if (targetLanguage == null || targetLanguage.isEmpty()) {
                return searchMessages(roomId, userId, keyword);
            }

            List<ChatMessage> recentMessages = chatMessageRepository.findTop1000ByChatRoomIdOrderBySentAtDesc(roomId);
            if (recentMessages.isEmpty()) {
                return List.of();
            }

            List<String> originalContents = recentMessages.stream().map(ChatMessage::getContent).toList();
            List<String> translatedContents = translationService.translateMessages(originalContents, targetLanguage);


            List<MessagePair> foundPairs = IntStream.range(0, recentMessages.size())
                    .mapToObj(i -> new MessagePair(recentMessages.get(i), translatedContents.get(i)))
                    .filter(pair -> pair.translatedContent().toLowerCase().contains(keyword.toLowerCase()))
                    .toList();

            if (foundPairs.isEmpty()) {
                return List.of();
            }

            List<Long> senderIds = foundPairs.stream().map(p -> p.originalMessage().getSenderId()).distinct().toList();
            Map<Long, UserResponseDto> senderInfoMap = userClient.getUsersInfo(senderIds).stream()
                    .collect(Collectors.toMap(UserResponseDto::userId, Function.identity()));

            return foundPairs.stream()
                    .map(pair -> {
                        UserResponseDto sender = senderInfoMap.getOrDefault(pair.originalMessage().getSenderId(), UserResponseDto.unknown());
                        return new ChatMessageResponse(
                                pair.originalMessage().getId(), pair.originalMessage().getChatRoomId(), sender.userId(),
                                pair.originalMessage().getContent(), pair.translatedContent(), pair.originalMessage().getSentAt(),
                                sender.firstName(), sender.lastName(), sender.ImageUrl()
                        );
                    })
                    .sorted(Comparator.comparing(ChatMessageResponse::sentAt).reversed())
                    .toList();
        }
    }

    /**
     * @apiNote [리팩토링 후] 메시지를 DB(MongoDB)에서 삭제하고, 해당 채팅방에 삭제 이벤트를 브로드캐스팅합니다.
     */
    @Transactional
    public void deleteMessageAndBroadcast(String messageId, Long userId) {
        ChatMessage message = chatMessageRepository.findById(messageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_MESSAGE_NOT_FOUND));

        if (!message.getSenderId().equals(userId)) {
            throw new BusinessException(ErrorCode.INVALID_USER_DELETE_MESSAGE);
        }

        Map<String, String> payload = Map.of(
                "id", messageId,
                "type", "delete"
        );
        chatMessageRepository.delete(message);
        String destination = "/topic/rooms/" + message.getChatRoomId();
        messagingTemplate.convertAndSend(destination, payload);
    }

    @Transactional
    public void processMarkAsRead(MarkAsReadRequest req) {
        Long userId = req.userId();
        Long roomId = req.roomId();
        String lastReadMessageId = req.lastReadMessageId();

        this.markMessagesAsRead(roomId, userId, lastReadMessageId);

        messagingTemplate.convertAndSend(
                "/topic/rooms/" + roomId + "/read-status",
                new ReadStatusResponse(roomId, userId, lastReadMessageId)
        );

        ChatRoom chatRoom = chatRoomService.getChatRoomById(roomId);
        List<ChatParticipant> participants = chatRoom.getParticipants();
        if (participants.isEmpty()) return;

        List<Long> userIds = participants.stream().map(ChatParticipant::getUserId).distinct().toList();
        Map<Long, UserResponseDto> userInfoMap = userClient.getUsersInfo(userIds)
                .stream()
                .collect(Collectors.toMap(UserResponseDto::userId, Function.identity()));

        String groupRoomImageUrl = null;
        if (chatRoom.getGroup()) {
            List<ImageDto> roomImages = userClient.getImagesForChatRooms(List.of(chatRoom.getId()));
            groupRoomImageUrl = roomImages.isEmpty() ? null : roomImages.getFirst().imageUrl();
        }


        String summaryRoomName;
        String summaryRoomImageUrl;

        if (chatRoom.getGroup()) {
            summaryRoomName = chatRoom.getRoomName();
            summaryRoomImageUrl = groupRoomImageUrl;
        } else {
            UserResponseDto otherUserInfo = participants.stream()
                    .filter(p -> !p.getUserId().equals(userId))
                    .findFirst()
                    .map(other -> userInfoMap.get(other.getUserId()))
                    .orElse(null);

            if (otherUserInfo != null) {
                summaryRoomName = otherUserInfo.firstName() + " " + otherUserInfo.lastName();
                summaryRoomImageUrl = otherUserInfo.ImageUrl();
            } else {
                summaryRoomName = "알 수 없는 대화";
                summaryRoomImageUrl = null;
            }
        }

        int unreadCount = this.countUnreadMessages(roomId, userId);
        ChatRoomSummaryResponse summary = ChatRoomSummaryResponse.from(
                chatRoom,
                this.getLastMessageContent(roomId),
                this.getLastMessageTime(roomId),
                unreadCount,
                summaryRoomName,
                summaryRoomImageUrl
        );

        messagingTemplate.convertAndSend("/topic/user/" + userId + "/rooms", summary);
    }
    /**
     * @apiNote 메시지 읽음 상태를 업데이트합니다.
     * 그룹 채팅에서 '누가 읽었는지'를 관리하는 로직입니다.
     *
     * @param roomId 메시지를 읽은 채팅방 ID
     * @param readerId 메시지를 읽은 사용자 ID
     * @param lastReadMessageId 마지막으로 읽은 메시지 ID
     */
    @Transactional
    public void markMessagesAsRead(Long roomId, Long readerId, String lastReadMessageId) { // Long -> String
        ChatParticipant readerParticipant = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, readerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_PARTICIPANT_NOT_FOUND));
        readerParticipant.updateLastReadMessageId(lastReadMessageId);
    }
    /**
     * [수정된 메서드]
     * 특정 채팅방의 모든 메시지를 특정 사용자에 대해 '읽음'으로 처리합니다.
     * 내부적으로 가장 마지막 메시지를 찾아 ID를 업데이트합니다.
     */
    @Transactional
    public void markAllMessagesAsReadInRoom(Long roomId, Long readerId) {
        chatMessageRepository.findTopByChatRoomIdOrderByIdDesc(roomId)
                .ifPresent(lastMessage -> {
                    String lastMessageId = lastMessage.getId();
                    markMessagesAsRead(roomId, readerId, lastMessageId);
                });
    }


    @Transactional(readOnly = true)
    public int countUnreadMessages(Long roomId, Long userId) {
        String lastReadId = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
                .map(ChatParticipant::getLastReadMessageId)
                .orElse(null);

        if (lastReadId == null) {
            return chatMessageRepository.countByChatRoomIdAndSenderIdNot(roomId, userId);
        } else {
            return chatMessageRepository.countByChatRoomIdAndIdGreaterThanAndSenderIdNot(roomId, lastReadId, userId);
        }
    }

    /**
     * [개선] 마지막 메시지의 내용을 반환합니다.
     */
    public String getLastMessageContent(Long roomId) {
        // 헬퍼 메서드를 호출하여 마지막 메시지를 조회하고, 내용(content)만 반환합니다.
        return getLastMessage(roomId)
                .map(ChatMessage::getContent)
                .orElse(null); // 메시지가 없으면 null 반환
    }

    /**
     * [개선] 마지막 메시지의 전송 시간을 반환합니다.
     */
    public Instant getLastMessageTime(Long roomId) {
        // 헬퍼 메서드를 호출하여 마지막 메시지를 조회하고, 시간(sentAt)만 반환합니다.
        return getLastMessage(roomId)
                .map(ChatMessage::getSentAt)
                .orElse(null); // 메시지가 없으면 null 반환
    }

    /**
     * [추가] DB 조회를 담당하는 private 헬퍼 메서드
     * DB 조회 로직을 한 곳으로 모아 중복을 제거합니다.
     */
    private Optional<ChatMessage> getLastMessage(Long roomId) {
        return chatMessageRepository.findTopByChatRoomIdOrderBySentAtDesc(roomId);
    }

}