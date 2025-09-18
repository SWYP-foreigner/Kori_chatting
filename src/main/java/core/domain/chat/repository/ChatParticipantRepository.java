package core.domain.chat.repository;

import core.domain.chat.entity.ChatParticipant;
import core.domain.chat.entity.ChatRoom;
import core.global.enums.ChatParticipantStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatParticipantRepository extends JpaRepository<ChatParticipant, Long> {

    Optional<ChatParticipant> findByChatRoomIdAndUserIdAndStatusIsNot(Long chatRoomId, Long userId, ChatParticipantStatus status);

    long countByChatRoomIdAndStatus(Long roomId, ChatParticipantStatus status);

    /**
     * 채팅방 ID와 유저 ID를 기준으로 특정 참여자 정보를 조회합니다.
     * 데이터가 존재하지 않을 수 있으므로 Optional<T>로 반환하는 것이 안전합니다.
     *
     * @param chatRoomId 채팅방의 ID (ChatRoom 엔티티의 id 필드)
     * @param userId 참여자의 ID (ChatParticipant 엔티티의 userId 필드)
     * @return Optional<ChatParticipant>
     */
    Optional<ChatParticipant> findByChatRoomIdAndUserId(Long chatRoomId, Long userId);

    /**
     * [추가된 메서드]
     * 특정 채팅방 ID에 해당하는 모든 참여자(ChatParticipant) 목록을 조회합니다.
     *
     * @param chatRoomId 조회할 채팅방의 ID
     * @return List<ChatParticipant> 참여자 엔티티 목록
     */
    List<ChatParticipant> findByChatRoomId(Long chatRoomId);

}