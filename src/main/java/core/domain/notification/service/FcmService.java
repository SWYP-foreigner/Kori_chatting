package core.domain.notification.service;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class FcmService {

    /**
     * 특정 기기 토큰으로 푸시 알림을 보냅니다.
     * (FirebaseApp이 초기화되지 않은 경우, 로그만 남기고 실제 발송은 건너뜁니다.)
     *
     * @param deviceToken 알림을 보낼 기기의 FCM 토큰
     * @param title       알림의 제목
     * @param body        알림의 내용
     * @param referenceId 알림 클릭 시 이동할 대상의 ID (예: 채팅방 ID)
     */
    public void sendPushNotification(String deviceToken, String title, String body, String referenceId) {

        if (FirebaseApp.getApps().isEmpty()) {
            log.warn("FirebaseApp is not initialized. Skipping push notification to token: {}", deviceToken);
            return;
        }
        Notification notification = Notification.builder()
                .setTitle(title)
                .setBody(body)
                .build();

        Message message = Message.builder()
                .setToken(deviceToken)
                .setNotification(notification)
                .putData("type", "chat")
                .putData("referenceId", referenceId)
                .build();

        try {
            String response = FirebaseMessaging.getInstance().send(message);
            log.info("Successfully sent message: " + response);
        } catch (FirebaseMessagingException e) {
            log.error("Failed to send FCM message to token: " + deviceToken, e);
        }
    }
}
