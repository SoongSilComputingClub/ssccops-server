package org.sscc.ssccopsserver.domain.notification.service;

import java.util.List;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.sscc.ssccopsserver.domain.operation.event.SubWorkTransitionedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/*
 * 하위 업무 전이를 듣는 자리 (ssccops#446 · ADR-0045).
 *
 * 세 겹으로 전이에서 떼어 놓는다 — 알림은 전이를 절대 막으면 안 되기 때문이다.
 *
 *   1. `AFTER_COMMIT` — 전이가 커밋된 뒤에만 돈다. 롤백된 전이의 알림은 없다.
 *   2. `@Async` — 요청 스레드가 아니라 `applicationTaskExecutor`에서 돈다. 푸시 서비스 왕복
 *      (구독당 최대 10초)이 승인 버튼의 응답 시간에 더해지지 않는다. `@EnableAsync`는
 *      `global/config/AsyncConfig`가 이 리스너를 위해 켰다.
 *   3. 알림 행은 `REQUIRES_NEW`(`SubWorkNotificationService`), 푸시는 그 트랜잭션이 닫힌 뒤
 *      (`PushDispatcher`). 여기서 나는 예외는 전부 잡아 ERROR 한 줄이다 — 비동기라 어차피 요청에
 *      닿지 않지만, 잡지 않으면 `SimpleAsyncUncaughtExceptionHandler`의 문구가 자리를 말하지 않는다.
 *
 * **AFTER_COMMIT 리스너 안에서 같은 트랜잭션에 쓰면 안 된다**는 스프링의 경고는 여기 해당하지
 * 않는다 — 비동기로 넘어가 새 스레드·새 트랜잭션이다. 동기로 되돌리면 `REQUIRES_NEW`가 그 경고의
 * 답이다.
 *
 * 테스트 함정: `@Transactional` 테스트는 커밋이 없어 이 리스너가 돌지 않는다 — 전용 H2에서 실제로
 * 커밋하고(`SubWorkNotificationListenerTest`) 비동기라 Awaitility로 기다린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubWorkTransitionNotificationListener {

    private final SubWorkNotificationService subWorkNotificationService;
    private final PushDispatcher pushDispatcher;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubWorkTransitioned(SubWorkTransitionedEvent event) {
        try {
            List<CreatedNotification> created =
                    subWorkNotificationService.createForTransition(event);
            pushDispatcher.dispatch(created);
        } catch (RuntimeException e) {
            log.error(
                    "notification for sub-work {} ({}) failed — the transition itself is committed",
                    event.subWorkId(),
                    event.action(),
                    e);
        }
    }
}
