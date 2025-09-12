package core.domain.chat.repository;

import core.domain.chat.dto.UnreadCountDto;
import core.domain.chat.entity.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface ChatMessageRepository extends MongoRepository<ChatMessage, String> {

    /**
     * 여러 채팅방 ID를 받아, 각 채팅방의 가장 최신 메시지를 조회합니다.
     *
     * @param roomIds 조회할 채팅방 ID 리스트
     * @return Map<채팅방 ID, 최신 ChatMessage 객체>
     */
    @Aggregation(pipeline = {
            "{ $match: { 'room_id': { $in: ?0 } } }",
            "{ $sort: { 'sent_at': -1 } }",
            "{ $group: { '_id': '$room_id', 'latestMessage': { $first: '$$ROOT' } } }",
            "{ $replaceRoot: { newRoot: '$latestMessage' } }"
    })
    List<ChatMessage> findLatestMessagesForRooms(List<Long> roomIds);


    /**
     * 여러 채팅방에 대해, 지정된 시간 이후에 온 메시지 수를 각각 카운트합니다.
     *
     * @param conditions 안 읽은 메시지 조건을 담은 객체 리스트
     * @return Map<채팅방 ID, 안 읽은 메시지 수>
     */
    @Aggregation(pipeline = {
            "{ $match: { $or: ?0 } }",
            "{ $group: { '_id': '$room_id', 'unreadCount': { $sum: 1 } } }",
            "{ $project: { 'roomId': '$_id', 'unreadCount': 1, '_id': 0 } }"
    })
    List<UnreadCountDto> countUnreadMessagesGroupedByRoomId(List<Map<String, Object>> conditions);
    void deleteByChatRoomId(Long chatRoomId);

    /**
     * 특정 시간 사이의 메시지를 조회합니다. (채팅방 나간 후 스크롤 시)
     */
    List<ChatMessage> findByChatRoomIdAndSentAtAfterAndSentAtBefore(
            Long chatRoomId, Instant sentAtAfter, Instant sentAtBefore, Pageable pageable
    );

    /**
     * 특정 시간 이후의 메시지를 조회합니다. (채팅방 나간 후 첫 페이지)
     */
    List<ChatMessage> findByChatRoomIdAndSentAtAfter(
            Long chatRoomId, Instant sentAtAfter, Pageable pageable
    );

    /**
     * 특정 시간 이전의 메시지를 조회합니다. (참여 중 스크롤 시)
     */
    List<ChatMessage> findByChatRoomIdAndSentAtBefore(
            Long chatRoomId, Instant sentAtBefore, Pageable pageable
    );

    List<ChatMessage> findByChatRoomId(
            Long chatRoomId, Pageable pageable
    );
    List<ChatMessage> findTop20ByChatRoomIdAndIdLessThanOrderByIdDesc(Long chatRoomId, String id);

    List<ChatMessage> findTop20ByChatRoomIdAndIdGreaterThanOrderByIdAsc(Long chatRoomId, String id);
}


