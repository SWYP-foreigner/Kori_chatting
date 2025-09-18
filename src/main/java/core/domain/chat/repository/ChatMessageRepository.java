package core.domain.chat.repository;

import core.domain.chat.dto.UnreadCountDto;
import core.domain.chat.entity.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ChatMessageRepository extends MongoRepository<ChatMessage, String> {

    /**
     * 특정 시간 이후의 메시지를 조회합니다. (채팅방 나간 후 첫 페이지)
     */
    List<ChatMessage> findByChatRoomIdAndSentAtAfter(
            Long chatRoomId, Instant sentAtAfter, Pageable pageable
    );


    List<ChatMessage> findByChatRoomId(
            Long chatRoomId, Pageable pageable
    );
    List<ChatMessage> findTop50ByChatRoomIdOrderBySentAtDesc(Long chatRoomId);
    /**
     * 특정 메시지 ID 이전의 메시지를 페이징 조회 (무한 스크롤)
     * JPA의 IdBefore -> MongoDB의 IdLessThan
     */
    List<ChatMessage> findByChatRoomIdAndIdLessThan(Long chatRoomId, String lastMessageId, Pageable pageable);

    /**
     * 사용자가 나간 시점 이후 & 특정 메시지 ID 이전의 메시지를 페이징 조회 (무한 스크롤)
     */
    List<ChatMessage> findByChatRoomIdAndSentAtAfterAndIdLessThan(Long chatRoomId, Instant lastLeftAt, String lastMessageId, Pageable pageable);

    List<ChatMessage> findByChatRoomIdAndContentContainingIgnoreCase(Long chatRoomId, String keyword);

    List<ChatMessage> findTop1000ByChatRoomIdOrderBySentAtDesc(Long chatRoomId);

    /**
     * [추가된 메서드]
     * 특정 채팅방에서 ID를 기준으로 가장 최신 메시지 1개를 조회합니다.
     * MongoDB의 ObjectId는 시간 정보를 포함하고 있으므로 ID 역순 정렬은 최신순 정렬과 같습니다.
     *
     * @param chatRoomId 조회할 채팅방의 ID
     * @return Optional<ChatMessage> 가장 최신 메시지
     */
    Optional<ChatMessage> findTopByChatRoomIdOrderByIdDesc(Long chatRoomId);

    /**
     * [추가] 마지막으로 읽은 메시지가 있을 때, 그보다 최신이면서 내가 보내지 않은 메시지의 수를 계산합니다.
     */
    int countByChatRoomIdAndIdGreaterThanAndSenderIdNot(Long chatRoomId, String lastReadMessageId, Long currentUserId);

    /**
     * [추가] 마지막으로 읽은 메시지가 없을 때 (null), 내가 보내지 않은 모든 메시지의 수를 계산합니다.
     */
    int countByChatRoomIdAndSenderIdNot(Long chatRoomId, Long currentUserId);

    /**
     * [추가] 특정 채팅방에서 시간을 기준으로 가장 최신 메시지 1개를 조회합니다.
     */
    Optional<ChatMessage> findTopByChatRoomIdOrderBySentAtDesc(Long chatRoomId);
}


