package org.sscc.ssccopsserver.domain.notification.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.notification.code.NotificationApp;
import org.sscc.ssccopsserver.domain.notification.code.NotificationTargetType;
import org.sscc.ssccopsserver.domain.notification.code.NotificationType;
import org.sscc.ssccopsserver.domain.notification.entity.NotificationEntity;

/*
 * 알림 한 건 (ssccops#446 API 계약의 `Notification`). 필드 이름은 그 계약 그대로다 — 웹이 이
 * 모양으로 먼저 가므로 바꾸면 web Sub-task에 알린다.
 *
 * 시각은 서비스 표준 시간대의 OffsetDateTime으로 내린다(다른 응답 DTO와 같다).
 */
public record NotificationResponse(
        Long notificationId,
        NotificationType type,
        String title,
        String body,
        NotificationApp app,
        String linkPath,
        NotificationTargetType targetType,
        Long targetId,
        OffsetDateTime readAt,
        OffsetDateTime createdAt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static NotificationResponse of(NotificationEntity notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getType(),
                notification.getTitle(),
                notification.getBody(),
                notification.getApp(),
                notification.getLinkPath(),
                notification.getTargetType(),
                notification.getTargetId(),
                toOffsetDateTime(notification.getReadAt()),
                toOffsetDateTime(notification.getCreatedAt()));
    }

    static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
