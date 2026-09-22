package org.sscc.ssccopsserver.domain.notification.service;

import java.time.Clock;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.notification.code.NotificationApp;
import org.sscc.ssccopsserver.domain.notification.code.error.NotificationErrorCode;
import org.sscc.ssccopsserver.domain.notification.dto.NotificationCursor;
import org.sscc.ssccopsserver.domain.notification.dto.NotificationListResponse;
import org.sscc.ssccopsserver.domain.notification.dto.NotificationReadAllResponse;
import org.sscc.ssccopsserver.domain.notification.dto.NotificationReadResponse;
import org.sscc.ssccopsserver.domain.notification.dto.NotificationResponse;
import org.sscc.ssccopsserver.domain.notification.dto.UnreadCountResponse;
import org.sscc.ssccopsserver.domain.notification.entity.NotificationEntity;
import org.sscc.ssccopsserver.domain.notification.repository.NotificationRepository;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

import lombok.RequiredArgsConstructor;

/*
 * 알림 목록·읽음의 구현 (ssccops#446).
 *
 * **남의 알림과 없는 알림은 같은 404다.** 조회는 id로 하고 소유는 엔티티에 묻는다(`isOwnedBy`) —
 * 조건에 회원을 넣어 «못 찾음»으로 만들면 결과는 같지만 «남의 것을 찾았다»를 여기서 구별할 수
 * 없어 로그도 남길 수 없다(ShareLinkServiceImpl.preview와 같은 판단).
 *
 * **앱 필터는 NotificationRoutingPolicy가 준 두 집합으로 건다**(#535 · ADR-0047). 이 클래스가
 * 기준표를 직접 읽지 않는 것은 발송(PushDispatcher)과 판정이 갈리지 않게 하기 위해서다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationRoutingPolicy routingPolicy;
    private final Clock clock;

    @Override
    public NotificationListResponse list(
            Long memberId, int size, String cursor, NotificationApp app) {
        NotificationCursor decoded = NotificationCursor.decode(cursor);
        Long cursorId = decoded == null ? null : decoded.id();

        // 다음 페이지가 있는지 알기 위해 한 건 더 읽는다
        List<NotificationEntity> fetched = page(memberId, cursorId, size + 1, app);
        boolean hasNext = fetched.size() > size;
        List<NotificationEntity> rows = hasNext ? fetched.subList(0, size) : fetched;

        return new NotificationListResponse(
                rows.stream().map(NotificationResponse::of).toList(),
                hasNext ? new NotificationCursor(rows.get(rows.size() - 1).getId()).encode() : null,
                countUnread(memberId, app));
    }

    @Override
    public UnreadCountResponse unreadCount(Long memberId, NotificationApp app) {
        return new UnreadCountResponse(countUnread(memberId, app));
    }

    /*
     * 필터가 없는 경로를 남겨 둔 것은 «전체» 칩이 그 자리이기 때문이다(#535) — 세 유형 집합을
     * 만들어 IN 두 개를 태우면 같은 답에 비용만 는다.
     */
    private List<NotificationEntity> page(
            Long memberId, Long cursorId, int limit, NotificationApp app) {
        if (app == null) {
            return notificationRepository.findPageByMemberId(
                    memberId, cursorId, PageRequest.of(0, limit));
        }
        return notificationRepository.findPageByMemberIdAndApp(
                memberId,
                cursorId,
                app,
                routingPolicy.typesRoutedTo(app),
                routingPolicy.typesFollowingSendingApp(),
                PageRequest.of(0, limit));
    }

    /** 목록의 unreadCount와 배지가 같은 필터를 지나게 하는 한 자리 */
    private long countUnread(Long memberId, NotificationApp app) {
        if (app == null) {
            return notificationRepository.countByMemberIdAndReadAtIsNull(memberId);
        }
        return notificationRepository.countUnreadByMemberIdAndApp(
                memberId,
                app,
                routingPolicy.typesRoutedTo(app),
                routingPolicy.typesFollowingSendingApp());
    }

    @Override
    @Transactional
    public NotificationReadResponse markRead(Long memberId, Long notificationId) {
        NotificationEntity notification =
                notificationRepository
                        .findById(notificationId)
                        .filter(found -> found.isOwnedBy(memberId))
                        .orElseThrow(
                                () ->
                                        new GeneralException(
                                                NotificationErrorCode.NOTIFICATION_NOT_FOUND));
        notification.markRead(clock.instant());
        return NotificationReadResponse.of(notification);
    }

    @Override
    @Transactional
    public NotificationReadAllResponse markAllRead(Long memberId) {
        return new NotificationReadAllResponse(
                notificationRepository.markAllRead(memberId, clock.instant()));
    }
}
