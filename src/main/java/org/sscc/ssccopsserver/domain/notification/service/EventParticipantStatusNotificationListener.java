package org.sscc.ssccopsserver.domain.notification.service;

import java.util.List;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.sscc.ssccopsserver.domain.event.event.EventParticipantStatusChangedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/*
 * 행사 참가 상태 변경을 듣는 자리 (#528 · ssccops#453 · ADR-0045).
 *
 * `SubWorkTransitionNotificationListener`와 같은 세 겹이다 — AFTER_COMMIT · `@Async` · 알림 행은
 * REQUIRES_NEW, 푸시는 그 뒤. 예외는 전부 잡아 ERROR 한 줄이다 — 알림은 명단 조작을 절대 막지
 * 않는다. 왜 이 모양인지는 그 리스너의 주석에 있다.
 *
 * 테스트 함정: `@Transactional` 테스트에서는 돌지 않는다(커밋 없음) —
 * `EventParticipantStatusNotificationListenerTest`가 전용 H2에서 실제로 커밋하고 Awaitility로
 * 기다린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventParticipantStatusNotificationListener {

    private final EventParticipantNotificationService eventParticipantNotificationService;
    private final PushDispatcher pushDispatcher;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEventParticipantStatusChanged(EventParticipantStatusChangedEvent event) {
        try {
            List<CreatedNotification> created =
                    eventParticipantNotificationService.createForStatusChange(event);
            pushDispatcher.dispatch(created);
        } catch (RuntimeException e) {
            log.error(
                    "notification for event participant {} ({} -> {}) failed — the change itself"
                            + " is committed",
                    event.eventParticipantId(),
                    event.previous(),
                    event.next(),
                    e);
        }
    }
}
