package core.domain.userdevicetoken.repository;

import core.domain.userdevicetoken.entity.UserDeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserDeviceTokenRepository extends JpaRepository<UserDeviceToken, Long> {

    /**
     * 특정 사용자의 모든 기기 토큰 목록을 조회합니다.
     * 한 명의 사용자가 여러 기기(폰, 태블릿 등)에 로그인한 경우를 대비하기 위함입니다.
     * @param userId 사용자 ID
     * @return 해당 사용자의 UserDeviceToken 목록
     */
    List<UserDeviceToken> findByUserId(Long userId);
}