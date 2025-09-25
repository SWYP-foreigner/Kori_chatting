package core.domain.notification.service;
import core.domain.notification.dto.NotificationEvent;
import core.domain.notification.entity.Notification;
import core.domain.notification.repository.NotificationRepository;
import core.domain.userdevicetoken.entity.UserDeviceToken;
import core.domain.userdevicetoken.repository.UserDeviceTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserDeviceTokenRepository userDeviceTokenRepository;
    private final FcmService fcmService;

    @Transactional
    public void createAndSendNotification(NotificationEvent event) {
        Notification notification = Notification.builder()
                .userId(event.userId())
                .message(event.message())
                .notificationType(event.type())
                .referenceId(event.referenceId())
                .build();
        notificationRepository.save(notification);

        List<UserDeviceToken> deviceTokens = userDeviceTokenRepository.findByUserId(event.userId());
        for (UserDeviceToken token : deviceTokens) {
            String notificationTitle = "새로운 메시지";
            fcmService.sendPushNotification(
                    token.getDeviceToken(),
                    notificationTitle,
                    event.message(),
                    String.valueOf(event.referenceId())
            );
        }
    }
}