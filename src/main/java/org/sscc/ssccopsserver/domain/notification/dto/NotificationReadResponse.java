package org.sscc.ssccopsserver.domain.notification.dto;

import java.time.OffsetDateTime;

import org.sscc.ssccopsserver.domain.notification.entity.NotificationEntity;

/** POST /v1/notifications/{id}/read — 처음 읽은 시각이 그대로 온다(두 번 읽어도 같다) */
public record NotificationReadResponse(Long notificationId, OffsetDateTime readAt) {

    public static NotificationReadResponse of(NotificationEntity notification) {
        return new NotificationReadResponse(
                notification.getId(),
                NotificationResponse.toOffsetDateTime(notification.getReadAt()));
    }
}
