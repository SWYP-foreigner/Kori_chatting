package core.domain.notification.dto;

import core.global.enums.NotificationType;

public record NotificationEvent(
        Long userId,
        NotificationType type,
        String message,
        Long referenceId,
        Long subjectId
) {
}