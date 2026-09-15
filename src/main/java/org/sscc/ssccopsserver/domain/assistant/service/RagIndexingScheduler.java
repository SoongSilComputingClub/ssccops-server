package org.sscc.ssccopsserver.domain.assistant.service;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/*
 * 색인 워커를 실제로 돌리는 자리 — 기동 복구 한 번 + 대기열 폴링 (#400 · 기획안 §12.4).
 *
 * ══ 워커와 나눈 이유 ════════════════════════════════════════════
 *
 * 색인의 규칙(`RagIndexingWorker`)과 «언제 도는가»는 수명이 다르다. 무엇보다 **테스트가 자동
 * 실행을 끄고 워커 메서드를 직접 부르기 때문에** 그 스위치가 워커 안에 있으면 안 된다 —
 * `ssccops.assistant.indexing.auto=false`면 이 빈 자체가 서지 않고, 그래서 테스트 컨텍스트에는
 * 폴링도 기동 복구도 없다. 켜 두면 컨텍스트가 뜨자마자 스텁 임베딩을 부르며 **상태가 테스트
 * 사이로 새어 나간다**(공용 `testdb`와 `InMemoryRagChunkStore`를 여럿이 나눠 쓴다).
 *
 * ══ 왜 `@Scheduled`가 아니라 자기 실행기인가 ════════════════════
 *
 * 세 가지를 한 자리에서 지키려는 것이다.
 *
 * 하나, **동시 실행 1건**(§11 · §12.4)이 단일 스레드 실행기라는 사실 자체로 성립한다 —
 * 스케줄러 풀을 남과 나눠 쓰면 그 크기가 곧 이 보장이 되고, 색인 한 건이 수십 초씩 그 풀을
 * 붙드는 것이 남의 작업에도 걸린다.
 *
 * 둘, **기동 복구가 첫 폴링보다 반드시 먼저다.** `@Scheduled`는 컨텍스트 새로고침 시점에 걸려
 * `ApplicationRunner`보다 먼저 돌 수 있는데, 그러면 복구가 **방금 집어 `INDEXING`이 된 문서를
 * 대기로 되돌려** 같은 문서를 두 번 색인한다. 여기서는 복구를 마친 뒤에 실행기를 켠다.
 *
 * 셋, `@EnableScheduling`을 앱 전체에 켜지 않는다 — 이 저장소에는 아직 스케줄링이 없고
 * (자동 마감 배치를 두지 않은 이유가 중복 실행이다), 한 기능을 위해 그 문을 여는 것은 대가가
 * 비대칭이다.
 *
 * ⚠️ **작업이 예외를 던지면 `scheduleWithFixedDelay`는 조용히 멈춘다.** 그래서 폴링 본문을
 * 통째로 감싼다 — 워커는 문서별 실패를 이미 `FAILED`로 흡수하지만, 그 밖(조회·커넥션)에서 난
 * 예외 하나로 색인이 재기동 전까지 영영 서 버리면 안 된다.
 */
/*
 * `matchIfMissing = true`는 `application.yaml`이 이 키를 선언한 뒤(#439)로는 닿지 않는 길이다.
 * 그래도 남기는 것은 **`@ConditionalOnProperty`가 「키가 없으면 부팅 실패」를 표현하지 못하기**
 * 때문이다 — 나머지 손잡이는 `@Value`에서 기본값을 빼 그 자리에서 깨지지만 이 값만은 깨질 수
 * 없으므로, 선언이 사라졌을 때의 동작을 애노테이션이 스스로 들고 있어야 한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "ssccops.assistant.indexing.auto",
        havingValue = "true",
        matchIfMissing = true)
public class RagIndexingScheduler implements ApplicationRunner, DisposableBean {

    private final RagIndexingWorker worker;
    private final Duration pollInterval;
    private final ScheduledExecutorService executor;

    public RagIndexingScheduler(
            RagIndexingWorker worker,
            @Value("${ssccops.assistant.indexing.poll-interval}") Duration pollInterval) {

        this.worker = worker;
        this.pollInterval = pollInterval;

        /*
         * 데몬 스레드 하나. 이름을 박는 것은 «색인이 무엇을 붙들고 있나»를 스레드 덤프에서
         * 바로 읽기 위해서이고, 데몬인 것은 종료가 이 스레드를 기다리지 않게 하기 위해서다 —
         * 진행 중이던 색인은 `INDEXING`으로 남고 다음 기동의 복구가 집는다.
         */
        ThreadFactory threads =
                runnable -> {
                    Thread thread = new Thread(runnable, "rag-indexing");
                    thread.setDaemon(true);
                    return thread;
                };
        this.executor = Executors.newSingleThreadScheduledExecutor(threads);
    }

    /*
     * 기동 복구는 폴링을 켜기 전에, 애플리케이션 스레드에서 한 번 돈다(위 «둘»).
     *
     * 폴링 자체에 초기 지연을 두지 않는 것은 배포 직후 «대기»로 남아 있던 문서가 곧바로 줄을
     * 이어 서야 하기 때문이다. 기능 플래그가 꺼져 있으면 워커의 두 메서드가 모두 즉시 돌아온다
     * — 플래그 판정을 여기 두지 않는 것은 그 값이 **워커의 계약**이라서다(직접 부르는 테스트에도
     * 같은 판정이 걸려야 한다).
     */
    @Override
    public void run(ApplicationArguments args) {
        worker.recoverStuckIndexing();

        executor.scheduleWithFixedDelay(
                this::poll, 0, Math.max(pollInterval.toMillis(), 1), TimeUnit.MILLISECONDS);

        log.info("규정 문서 색인 워커를 켰다 — 폴링 주기 {}", pollInterval);
    }

    private void poll() {
        try {
            worker.drainQueue();
        } catch (RuntimeException exception) {
            // 삼키는 것이 의도다(클래스 주석) — 여기서 올리면 폴링이 영영 멈춘다
            log.error("규정 문서 색인 대기열을 도는 중 오류가 났다. 다음 주기에 다시 시도한다", exception);
        }
    }

    /*
     * 실행기를 내린다. **진행 중인 색인을 기다리지 않는다**(`shutdownNow`) — 기다리면 배포가 그
     * 문서의 임베딩이 끝날 때까지 늦고, 기다려 봐야 그 트랜잭션은 이미 «집기»에서 커밋돼 있어
     * 중단이 남기는 상태(`INDEXING`)가 같다. 그 행은 다음 기동의 복구가 집는다.
     */
    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
