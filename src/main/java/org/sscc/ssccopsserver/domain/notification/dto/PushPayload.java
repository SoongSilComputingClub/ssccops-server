package org.sscc.ssccopsserver.domain.notification.dto;

import org.sscc.ssccopsserver.domain.notification.code.NotificationApp;
import org.sscc.ssccopsserver.domain.notification.code.NotificationType;
import org.sscc.ssccopsserver.domain.notification.entity.NotificationEntity;

/*
 * 푸시 페이로드 (ssccops#446 계약 `{ notificationId, type, title, body, app, linkPath }`).
 *
 * 서비스워커가 받아 알림을 그리고, 누르면 `linkPath`로 이동한다. **절대 URL이 없다** — 서비스워커가
 * 자기 origin으로 만든다(서버는 앱 origin을 모르고 env를 늘리지 않는다). JSON 4KB 이하여야 하며
 * 제목 200·내용 500자 상한(컬럼 길이)이 그것을 보장한다.
 */
public record PushPayload(
        Long notificationId,
        NotificationType type,
        String title,
        String body,
        NotificationApp app,
        String linkPath) {

    public static PushPayload of(NotificationEntity notification) {
        return new PushPayload(
                notification.getId(),
                notification.getType(),
                notification.getTitle(),
                notification.getBody(),
                notification.getApp(),
                notification.getLinkPath());
    }
}
