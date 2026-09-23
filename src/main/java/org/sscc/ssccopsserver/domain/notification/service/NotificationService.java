package org.sscc.ssccopsserver.domain.notification.service;

import org.sscc.ssccopsserver.domain.notification.code.NotificationApp;
import org.sscc.ssccopsserver.domain.notification.dto.NotificationListResponse;
import org.sscc.ssccopsserver.domain.notification.dto.NotificationReadAllResponse;
import org.sscc.ssccopsserver.domain.notification.dto.NotificationReadResponse;
import org.sscc.ssccopsserver.domain.notification.dto.UnreadCountResponse;

/*
 * 알림 목록·읽음 (ssccops#446). 전부 «내 것»이다 — 회원 식별자가 모든 메서드의 첫 인자다.
 */
public interface NotificationService {

    /**
     * 최신부터 커서로. {@code unreadCount}를 함께 싣는다(#446 계약).
     *
     * <p>{@code app}이 null이면 내 알림 전부이고, 값이 있으면 그 앱이 수신 앱인 것만이다(#535 · ADR-0047). 함께 싣는 {@code
     * unreadCount}도 **같은 필터**를 지난다 — 목록은 좁은데 배지가 넓으면 «읽을 것이 있다는데 아무것도 없다»가 된다.
     */
    NotificationListResponse list(Long memberId, int size, String cursor, NotificationApp app);

    /** 배지 하나. {@code app}이 null이면 전부 (#535) */
    UnreadCountResponse unreadCount(Long memberId, NotificationApp app);

    /** 없거나 남의 알림은 404 NOT_FOUND. 이미 읽은 것은 그대로 200(처음 읽은 시각) */
    NotificationReadResponse markRead(Long memberId, Long notificationId);

    NotificationReadAllResponse markAllRead(Long memberId);
}
