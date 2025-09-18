package core.domain.chat.repository;

import core.domain.chat.entity.ChatRoom;
import core.global.enums.ChatParticipantStatus;
import org.springframework.data.domain.Pageable;
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


    List<ChatRoom> findTop10ByGroupTrueOrderByCreatedAtDesc();

    @Query("SELECT cr FROM ChatRoom cr WHERE cr.group = true ORDER BY SIZE(cr.participants) DESC")
    List<ChatRoom> findPopularGroupChats(Pageable pageable);

    List<ChatRoom> findTop10ByGroupTrueAndIdLessThanOrderByCreatedAtDesc(Long id);
    /**
     * [추가된 메서드]
     * 특정 사용자가 'ACTIVE' 상태로 참여하고 있는 모든 채팅방 목록을 조회합니다.
     */
    @Query("SELECT cr FROM ChatRoom cr JOIN cr.participants p WHERE p.userId = :userId AND p.status = :status")
    List<ChatRoom> findActiveChatRoomsByUserId(@Param("userId") Long userId, @Param("status") ChatParticipantStatus status);



    @Query("SELECT cr FROM ChatRoom cr WHERE cr.group = false " +
            "AND (SELECT COUNT(p) FROM ChatParticipant p WHERE p.chatRoom = cr AND p.userId IN (:userId1, :userId2)) = 2 " +
            "AND (SELECT COUNT(p) FROM ChatParticipant p WHERE p.chatRoom = cr) = 2")
    List<ChatRoom>  findOneToOneRoomByParticipantIds(@Param("userId1") Long userId1, @Param("userId2") Long userId2);


    /**
     * [추가된 메서드]
     * 채팅방 ID로 조회 시, 연관된 참여자(participants) 목록까지 JOIN FETCH하여
     * N+1 문제 없이 한 번의 쿼리로 가져옵니다.
     *
     * @param roomId 조회할 채팅방의 ID
     * @return Optional<ChatRoom> (참여자 정보가 포함됨)
     */
    @Query("SELECT cr FROM ChatRoom cr JOIN FETCH cr.participants p WHERE cr.id = :roomId")
    Optional<ChatRoom> findByIdWithParticipants(@Param("roomId") Long roomId);

}