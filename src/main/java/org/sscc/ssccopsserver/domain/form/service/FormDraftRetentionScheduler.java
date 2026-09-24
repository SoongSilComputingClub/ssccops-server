package org.sscc.ssccopsserver.domain.form.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 접수가 끝난 폼에 남은 미제출 초안의 보존 기간 정리 — <b>시각만 정한다</b> (#557 · ssccops#502 · #36 결정).
 *
 * <p>{@code domain/form/AGENTS.md}가 «폼이 CLOSED된 뒤 남은 DRAFT는 접수 종료 후 90일까지 보존하고 그 뒤 삭제한다»고 적고, 영구
 * 보존하지 않는 근거를 «미제출 초안은 지원 의사를 남기지 않은 개인정보라 보관 근거가 없다»로 들었다. <b>그런데 그 배치가 없어 정책이 한 번도 실행되지 않았다</b> —
 * 수동 {@code DELETE}를 누가 언제 돌렸다는 기록도 저장소에 없다.
 *
 * <p>미루던 근거는 «다중 인스턴스에서 스케줄러 중복 실행을 막을 장치가 없다»였다. 그것은 이 종류의 작업에 애초에 필요 없었고, 이 저장소가 이미 코드로 증명해 두었다 —
 * {@code DeadlineNotificationScheduler.purgeReadNotifications}가 같은 모양의 멱등한 DELETE로 매주 돈다. 인스턴스가 둘
 * 떠도(배포 겹침) 결과가 같다.
 *
 * <p><b>삭제 자체는 {@link FormDraftRetentionService}에 있다 — 같은 클래스에 두면 트랜잭션이 걸리지 않는다</b> (#570). {@code
 * this.purge()}는 Spring 프록시를 지나지 않아 {@code @Transactional}이 무시되고, 벌크 삭제는 트랜잭션 없이는 죽는다. 그 예외를 아래
 * {@code catch}가 받아 주 1회 로그 한 줄로만 남을 자리였다. 그 이유는 서비스 쪽 주석에 적어 두었다.
 *
 * <p>여기서 던진 예외는 사용자에게 갈 곳이 없어 삼키고 로그로 남긴다({@code DeadlineNotificationScheduler}가 같은 판단을 한 자리다) — 다만
 * <b>삼키는 자리가 있다는 것 자체가 위험</b>이라, 실패가 로그에만 남는다는 사실을 메시지에 적는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FormDraftRetentionScheduler {

    private final FormDraftRetentionService formDraftRetentionService;

    /**
     * 매주 월요일 05:00 KST.
     *
     * <p>04:00은 {@code DatabaseKeepAlive}, 04:30은 알림 정리다. 같은 시각에 겹치면 Supabase Free의 커넥션을 셋이 나눠 쓴다.
     */
    @Scheduled(cron = "0 0 5 * * MON", zone = "Asia/Seoul")
    public void purgeClosedFormDrafts() {
        try {
            formDraftRetentionService.purge();
        } catch (RuntimeException e) {
            log.error("closed form draft purge failed — 다음 주에 다시 돈다", e);
        }
    }
}
