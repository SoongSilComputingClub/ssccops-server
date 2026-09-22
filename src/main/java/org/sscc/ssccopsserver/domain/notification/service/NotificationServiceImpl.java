package org.sscc.ssccopsserver.domain.notification.service;

import java.time.Clock;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;
    private final Clock clock;

    @Override
    public NotificationListResponse list(Long memberId, int size, String cursor) {
        NotificationCursor decoded = NotificationCursor.decode(cursor);
        Long cursorId = decoded == null ? null : decoded.id();

        // 다음 페이지가 있는지 알기 위해 한 건 더 읽는다
        List<NotificationEntity> fetched =
                notificationRepository.findPageByMemberId(
                        memberId, cursorId, PageRequest.of(0, size + 1));
        boolean hasNext = fetched.size() > size;
        List<NotificationEntity> rows = hasNext ? fetched.subList(0, size) : fetched;

        return new NotificationListResponse(
                rows.stream().map(NotificationResponse::of).toList(),
                hasNext ? new NotificationCursor(rows.get(rows.size() - 1).getId()).encode() : null,
                notificationRepository.countByMemberIdAndReadAtIsNull(memberId));
    }

    @Override
    public UnreadCountResponse unreadCount(Long memberId) {
        return new UnreadCountResponse(
                notificationRepository.countByMemberIdAndReadAtIsNull(memberId));
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
