package org.sscc.ssccopsserver.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/*
 * `@Async`의 문을 연다 (ssccops#446 · ADR-0045).
 *
 * 첫 손님은 알림 도메인의 `SubWorkTransitionNotificationListener`다 — 하위 업무 전이가 커밋된 뒤
 * 알림 행을 만들고 푸시 서비스에 HTTP를 보내는데(구독당 최대 10초), 그 시간이 승인 버튼의 응답에
 * 더해지면 안 된다. `@TransactionalEventListener(AFTER_COMMIT)`만으로는 **같은 요청 스레드**에서
 * 커밋 직후 돌아 응답이 그만큼 늦는다.
 *
 * 실행기는 Boot가 자동 구성하는 `applicationTaskExecutor`(별칭 `taskExecutor` · 코어 8)다 —
 * 이 저장소에 `Executor` 빈이 없어 자동 구성이 물러나지 않는다(RagIndexingScheduler는 빈이 아니라
 * 자기 필드로 실행기를 든다). 알림 발송 하나에 전용 풀을 만들지 않는 것은 `SchedulingConfig`가
 * 스레드 하나로 시작한 것과 같은 판단이다 — 서로를 막기 시작하면 그때 `spring.task.execution.pool`을
 * 올린다.
 *
 * `@Async` 메서드의 예외는 호출자에게 닿지 않는다 — 리스너가 스스로 잡아 ERROR 로그를 남긴다.
 * `SchedulingConfig` 옆에 둔 것은 «비동기·스케줄 두 문이 어디서 열렸나»를 한 자리에서 보기 위해서다.
 */
@Configuration
@EnableAsync
public class AsyncConfig {}
