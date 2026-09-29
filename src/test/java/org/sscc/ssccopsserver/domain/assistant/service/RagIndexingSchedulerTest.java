package org.sscc.ssccopsserver.domain.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/*
 * 워커를 «언제 돌리는가» (#400 · 기획안 §12.4).
 *
 * 컨텍스트를 띄우지 않는다 — 확인하려는 것이 배선이 아니라 **순서와 되살아남**이기 때문이다.
 *
 * ① **기동 복구가 첫 폴링보다 먼저다.** 뒤집히면 복구가 방금 집어 `INDEXING`이 된 문서를 대기로
 *    되돌려 같은 문서를 두 번 색인한다 — `@Scheduled`를 쓰지 않은 이유의 절반이 이것이다.
 * ② **폴링이 예외 하나로 멈추지 않는다.** `scheduleWithFixedDelay`는 작업이 예외를 던지면
 *    조용히 취소되므로, 삼키지 않으면 색인이 재기동 전까지 영영 선다.
 */
class RagIndexingSchedulerTest {

    /* 테스트가 기다리는 시간이지 운영값이 아니다 — 기본값은 PT10S다 */
    private static final Duration FAST = Duration.ofMillis(20);

    private final RagIndexingWorker worker = mock(RagIndexingWorker.class);
    private RagIndexingScheduler scheduler;

    @AfterEach
    void stop() {
        if (scheduler != null) {
            scheduler.destroy();
        }
    }

    @Test
    void recoversStuckDocumentsBeforeTheFirstPoll() throws Exception {
        CountDownLatch polled = new CountDownLatch(1);
        when(worker.drainQueue())
                .thenAnswer(
                        invocation -> {
                            polled.countDown();
                            return 0;
                        });

        scheduler = new RagIndexingScheduler(worker, FAST);
        scheduler.run(null);

        assertThat(polled.await(5, TimeUnit.SECONDS)).isTrue();

        /*
         * **세기 전에 세운다.** 폴링은 20ms 간격으로 계속 돌기 때문에, 빗장이 풀린 뒤 아래 검증이
         * 도는 사이에 다음 회차가 또 `drainQueue()`를 부른다 — 실제로 «Wanted 1 time but was 2
         * times»로 깨졌다(#563 작업 중). 여기서 보려는 것은 **순서**이지 횟수가 아니므로
         * `atLeastOnce()`로 적고, 그래도 실행기를 먼저 내려 검증이 움직이는 대상을 보지 않게 한다.
         */
        scheduler.destroy();

        InOrder order = inOrder(worker);
        order.verify(worker).recoverStuckIndexing();
        order.verify(worker, atLeastOnce()).drainQueue();
    }

    @Test
    void keepsPollingAfterAFailedRound() throws Exception {
        CountDownLatch rounds = new CountDownLatch(2);
        when(worker.drainQueue())
                .thenAnswer(
                        invocation -> {
                            rounds.countDown();
                            throw new IllegalStateException("커넥션을 얻지 못했다");
                        });

        scheduler = new RagIndexingScheduler(worker, FAST);
        scheduler.run(null);

        assertThat(rounds.await(5, TimeUnit.SECONDS))
                .as("첫 바퀴의 예외를 삼키지 않으면 두 번째 바퀴가 오지 않는다")
                .isTrue();
    }
}
