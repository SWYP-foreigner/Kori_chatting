package core.domain.chat.repository;

import core.domain.chat.entity.ChatRoom;
import core.global.enums.ChatParticipantStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

    @Query("SELECT cr FROM ChatRoom cr WHERE cr.group = true AND LOWER(cr.roomName) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<ChatRoom> findGroupChatRoomsByKeyword(@Param("keyword") String keyword);
    @Query("""
            select distinct cr
            from ChatRoom cr
            left join fetch cr.participants cp
            left join fetch cp.user u
            where exists (
              select 1 from ChatParticipant c1
              where c1.chatRoom = cr and c1.user.id = :currentUserId
            )
            and exists (
              select 1 from ChatParticipant c2
              where c2.chatRoom = cr and c2.user.id = :otherUserId
            )
            and size(cr.participants) = 2
            """)
    Optional<ChatRoom> findByParticipantIds(Long currentUserId, Long otherUserId);


    List<ChatRoom> findTop10ByGroupTrueOrderByCreatedAtDesc();

    @Query("SELECT cr FROM ChatRoom cr " +
            "WHERE cr.group = true " +
            "ORDER BY SIZE(cr.participants) DESC")
    List<ChatRoom> findTopByGroupTrueOrderByParticipantCountDesc(int limit);

    List<ChatRoom> findTop10ByGroupTrueAndIdLessThanOrderByCreatedAtDesc(Long id);
    @Query("SELECT cr FROM ChatRoom cr JOIN FETCH cr.participants p JOIN FETCH p.user WHERE cr.id = :roomId")
    Optional<ChatRoom> findByIdWithParticipantsAndUsers(@Param("roomId") Long roomId);



    @Query("SELECT cr FROM ChatRoom cr WHERE cr.group = false " +
            "AND (SELECT COUNT(p) FROM ChatParticipant p WHERE p.chatRoom = cr AND p.userId IN (:userId1, :userId2)) = 2 " +
            "AND (SELECT COUNT(p) FROM ChatParticipant p WHERE p.chatRoom = cr) = 2")
    Optional<ChatRoom> findOneToOneRoomByParticipantIds(@Param("userId1") Long userId1, @Param("userId2") Long userId2);

    /**
     * 특정 사용자가 'ACTIVE' 상태로 참여하고 있는 모든 채팅방 목록을 조회합니다.
     * N+1 문제를 방지하기 위해 참가자(participants) 정보까지 한 번의 쿼리로 함께 가져옵니다.
     *
     * @param userId 조회할 사용자의 ID
     * @param status 조회할 참가자의 상태 (예: ChatParticipantStatus.ACTIVE)
     * @return ChatRoom 리스트
     */
    @Query("SELECT DISTINCT cr FROM ChatRoom cr " +
            "JOIN FETCH cr.participants p " +
            "WHERE p.userId = :userId AND p.status = :status")
    List<ChatRoom> findActiveChatRoomsWithParticipantsByUserId(
            @Param("userId") Long userId,
            @Param("status") ChatParticipantStatus status
    );

}