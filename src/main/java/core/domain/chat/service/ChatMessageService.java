package core.domain.chat.service;


import core.domain.chat.dto.ChatMessageFirstResponse;
import core.domain.chat.dto.ChatMessageResponse;
import core.domain.chat.dto.ChatRoomSummaryResponse;
import core.domain.chat.dto.SendMessageRequest;
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
import core.global.image.entity.Image;
import core.global.image.repository.ImageRepository;
import core.global.service.TranslationService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatMessageService {

    private final ChatMessageRepository chatMessageRepo;
    private final ChatParticipantRepository participantRepo;
    private final ChatRoomRepository chatRoomRepo;
    private final ImageRepository imageRepository;
    private final TranslationService translationService;
    private final SimpMessagingTemplate messagingTemplate;
    private final ApplicationEventPublisher eventPublisher;

    private static final int MESSAGE_PAGE_SIZE = 20;
    private record MessagePair(ChatMessage originalMessage, String translatedContent) {}

    /**
     * @apiNote 채팅방 메시지를 조회하고, 번역 요청에 따라 ChatMessageResponse 목록을 반환합니다.
     * 이 메서드가 컨트롤러에서 호출되는 주된 엔드포인트가 됩니다.
     *
     * @param roomId 채팅방 ID
     * @param userId 조회하는 사용자 ID
     * @param lastMessageId 마지막으로 조회된 메시지 ID (무한 스크롤용)
     * @return ChatMessageResponse 목록
     */


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

    /**
     * @apiNote 채팅방 메시지를 무한 스크롤로 조회하는 핵심 로직입니다.
     * 이 메서드는 항상 ChatMessage 엔티티 목록을 반환합니다.
     *
     * @param roomId 채팅방 ID
     * @param userId 조회하는 사용자 ID
     * @param lastMessageId 마지막으로 조회된 메시지 ID (무한 스크롤용)
     * @return ChatMessage 엔티티 목록
     */
    @Transactional(readOnly = true)
    public List<ChatMessage> getRawMessages(Long roomId, Long userId, Long lastMessageId) {
        ChatParticipant participant = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_PARTICIPANT_NOT_FOUND));

        if (participant.getStatus() == ChatParticipantStatus.LEFT && participant.getLastLeftAt() != null) {
            Instant lastLeftAt = participant.getLastLeftAt();

            if (lastMessageId != null) {
                return chatMessageRepository.findByChatRoomIdAndSentAtAfterAndIdBefore(
                        roomId, lastLeftAt, lastMessageId,
                        PageRequest.of(0, MESSAGE_PAGE_SIZE, Sort.by("sentAt").descending())
                );
            } else {
                return chatMessageRepository.findByChatRoomIdAndSentAtAfter(
                        roomId, lastLeftAt,
                        PageRequest.of(0, MESSAGE_PAGE_SIZE, Sort.by("sentAt").descending())
                );
            }
        } else {
            if (lastMessageId != null) {
                return chatMessageRepository.findByChatRoomIdAndIdBefore(
                        roomId, lastMessageId,
                        PageRequest.of(0, MESSAGE_PAGE_SIZE, Sort.by("sentAt").descending())
                );
            } else {
                return chatMessageRepository.findByChatRoomId(
                        roomId,
                        PageRequest.of(0, MESSAGE_PAGE_SIZE, Sort.by("sentAt").descending())
                );
            }
        }
    }

    @Transactional
    public List<ChatMessageFirstResponse> getFirstMessages(Long roomId, Long userId) {
        ChatRoom chatRoom = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("채팅방을 찾을 수 없습니다."));

        chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
                .filter(participant -> participant.getStatus() != ChatParticipantStatus.LEFT)
                .orElseThrow(() -> new IllegalArgumentException("채팅방에 참여하지 않았거나 나간 사용자입니다."));
        List<ChatMessage> messages = chatMessageRepository.findTop50ByChatRoomIdOrderBySentAtDesc(roomId);

        return messages.stream()
                .map(message -> ChatMessageFirstResponse.fromEntity(message, chatRoom, imageRepository))
                .collect(Collectors.toList());
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

        chatParticipantRepository.findByChatRoomIdAndUserId(req.roomId(), req.senderId())
                .ifPresent(participant -> {
                    participant.setLastReadMessageId(savedMessage.getId());
                    chatParticipantRepository.save(participant);
                });

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
            if (!recipient.getId().equals(req.senderId())) {

                String notificationMessage = senderUser.getFirstName() + "님으로부터 새로운 메시지";

                NotificationEvent event = new NotificationEvent(
                        recipient.getId(),
                        NotificationType.chat,
                        notificationMessage,
                        chatRoom.getId(),
                        senderUser
                );

                eventPublisher.publishEvent(event);
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
                    .sorted(Comparator.comparing(ChatMessageResponse::sentAt, Comparator.reverseOrder()))
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
                    .sorted(Comparator.comparing(ChatMessageResponse::sentAt, Comparator.reverseOrder()))
                    .collect(Collectors.toList());
        }
    }
    /**
     * @apiNote 메시지를 DB에서 삭제하고, 해당 채팅방에 삭제 이벤트를 브로드캐스팅합니다.
     */
    @Transactional
    public void deleteMessageAndBroadcast(Long messageId, Long userId) {
        ChatMessage message = chatMessageRepository.findById(messageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MESSAGE_NOT_FOUND));

        if (!message.getSender().getId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN_MESSAGE_DELETE);
        }
        Map<String, String> payload = Map.of(
                "id", messageId.toString(),
                "type", "delete"
        );

        chatMessageRepository.delete(message);
        String destination = "/topic/rooms/" + message.getChatRoom().getId();
        messagingTemplate.convertAndSend(destination,payload);
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
    public void markMessagesAsRead(Long roomId, Long readerId, Long lastReadMessageId) {
        ChatParticipant readerParticipant = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, readerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_PARTICIPANT_NOT_FOUND));
        readerParticipant.setLastReadMessageId(lastReadMessageId);
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

    public int countUnreadMessages(Long roomId, Long userId) {
        Long lastReadId = chatParticipantRepository.findByChatRoomIdAndUserId(roomId, userId)
                .map(ChatParticipant::getLastReadMessageId)
                .orElse(0L);

        return chatMessageRepository.countUnreadMessages(roomId, lastReadId, userId);
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

}