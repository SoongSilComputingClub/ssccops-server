package org.sscc.ssccopsserver.domain.notification.service;

import java.util.List;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.sscc.ssccopsserver.domain.form.event.FormResponseReviewedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/*
 * 폼 응답 검토를 듣는 자리 (#528 · ssccops#453 · ADR-0045).
 *
 * `SubWorkTransitionNotificationListener`와 같은 세 겹이다 — AFTER_COMMIT(롤백된 검토의 알림은
 * 없다) · `@Async`(푸시 왕복이 검토 버튼의 응답 시간에 더해지지 않는다) · 알림 행은 REQUIRES_NEW,
 * 푸시는 그 트랜잭션이 닫힌 뒤. 예외는 전부 잡아 ERROR 한 줄이다 — 알림은 검토를 절대 막지
 * 않는다. 왜 이 모양인지는 그 리스너의 주석에 있다.
 *
 * 테스트 함정: `@Transactional` 테스트에서는 돌지 않는다(커밋 없음) —
 * `FormResponseReviewNotificationListenerTest`가 전용 H2에서 실제로 커밋하고 Awaitility로 기다린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FormResponseReviewNotificationListener {

    private final FormResponseNotificationService formResponseNotificationService;
    private final PushDispatcher pushDispatcher;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFormResponseReviewed(FormResponseReviewedEvent event) {
        try {
            List<CreatedNotification> created =
                    formResponseNotificationService.createForReview(event);
            pushDispatcher.dispatch(created);
        } catch (RuntimeException e) {
            log.error(
                    "notification for form response {} ({}) failed — the review itself is"
                            + " committed",
                    event.formResponseId(),
                    event.action(),
                    e);
        }
    }
}
