package org.sscc.ssccopsserver.domain.notification.service;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;
import org.sscc.ssccopsserver.domain.event.entity.EventParticipantEntity;
import org.sscc.ssccopsserver.domain.event.event.EventParticipantStatusChangedEvent;
import org.sscc.ssccopsserver.domain.event.repository.EventParticipantRepository;
import org.sscc.ssccopsserver.domain.notification.code.NotificationApp;
import org.sscc.ssccopsserver.domain.notification.code.NotificationTargetType;
import org.sscc.ssccopsserver.domain.notification.code.NotificationType;
import org.sscc.ssccopsserver.domain.notification.entity.NotificationEntity;
import org.sscc.ssccopsserver.domain.notification.repository.NotificationRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/*
 * 행사 참가 상태 변경 → 알림 행 (#528 · ssccops#453 · ADR-0045).
 *
 * 수신자는 **그 참가자 한 사람**이다. 바꾼 사람이 참가자 본인이면 생략한다(본인 취소 —
 * `SubWorkNotificationService`와 같은 규칙). 지금 명단을 바꾸는 경로는 전부 운영자의 것이라
 * 실제로는 거의 오지 않는 분기지만, 본인 철회(ADR-0011 «지금 만들지 않는다»)가 열리면 그 자리가
 * 이 조건 하나로 조용해진다.
 *
 * **REQUIRES_NEW다.** 부르는 쪽(`EventParticipantStatusNotificationListener`)이 커밋 뒤 비동기
 * 스레드라 바깥 트랜잭션은 없지만, «명단 변경과 다른 트랜잭션»이라는 사실을 선언으로 남긴다.
 *
 * 명단 행을 **다시 읽고** 알림 종류는 **이벤트가 실어 온 도달 상태**로 정한다 — 행의 현재 상태로
 * 정하면 비동기로 늦게 도는 사이 한 번 더 바뀐 상태를 «그 사건»으로 알리게 된다(승격 직후 취소면
 * 승격 알림이 취소로 둔갑한다). 문구의 행사명·일시는 그때의 행 값이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventParticipantNotificationService {

    private final EventParticipantRepository eventParticipantRepository;
    private final NotificationRepository notificationRepository;

    /** 상태 변경 하나에 대한 알림 행. 참가자가 바꾼 사람 본인이면 빈 목록 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<CreatedNotification> createForStatusChange(
            EventParticipantStatusChangedEvent event) {
        Optional<EventParticipantEntity> found =
                eventParticipantRepository.findById(event.eventParticipantId());
        if (found.isEmpty()) {
            log.info(
                    "event participant {} not found for notification — skipped ({})",
                    event.eventParticipantId(),
                    event.next());
            return List.of();
        }
        EventParticipantEntity participant = found.get();
        if (participant.getMember().getId().equals(event.performerId())) {
            return List.of();
        }

        NotificationType type = typeOf(event.next());
        NotificationEntity saved =
                notificationRepository.save(
                        NotificationEntity.create(
                                participant.getMember(),
                                type,
                                EventParticipantNotificationText.title(type, participant),
                                EventParticipantNotificationText.body(participant),
                                NotificationApp.WWW,
                                EventParticipantNotificationText.linkPath(),
                                NotificationTargetType.EVENT_PARTICIPANT,
                                participant.getId(),
                                null));
        return List.of(CreatedNotification.of(saved));
    }

    private static NotificationType typeOf(EventParticipantStatus status) {
        return switch (status) {
            case CONFIRMED -> NotificationType.APPLICATION_CONFIRMED;
            case WAITLISTED -> NotificationType.APPLICATION_WAITLISTED;
            case CANCELLED -> NotificationType.APPLICATION_CANCELLED;
        };
    }
}
