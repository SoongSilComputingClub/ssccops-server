package org.sscc.ssccopsserver.domain.form.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseHistoryRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 접수가 끝난 폼에 남은 미제출 초안의 보존 기간 정리 (#557 · ssccops#502 · #36 결정).
 *
 * <p>{@code domain/form/AGENTS.md}가 «폼이 CLOSED된 뒤 남은 DRAFT는 접수 종료 후 90일까지 보존하고 그 뒤 삭제한다»고 적고, 영구
 * 보존하지 않는 근거를 «미제출 초안은 지원 의사를 남기지 않은 개인정보라 보관 근거가 없다»로 들었다. <b>그런데 그 배치가 없어 정책이 한 번도 실행되지 않았다</b> —
 * 수동 {@code DELETE}를 누가 언제 돌렸다는 기록도 저장소에 없다.
 *
 * <p>미루던 근거는 «다중 인스턴스에서 스케줄러 중복 실행을 막을 장치가 없다»였다. 그것은 이 종류의 작업에 애초에 필요 없었고, 이 저장소가 이미 코드로 증명해 두었다 —
 * {@code DeadlineNotificationScheduler.purgeReadNotifications}가 같은 모양의 멱등한 DELETE로 매주 돈다. 인스턴스가 둘
 * 떠도(배포 겹침) 결과가 같다.
 *
 * <p><b>왜 별도 클래스인가.</b> {@code FormServiceImpl}은 요청이 부르는 서비스이고 이것은 시간이 부르는 작업이라 실패의 뜻이 다르다 — 여기서 던진
 * 예외는 사용자에게 갈 곳이 없어 삼키고 로그로 남긴다({@code DeadlineNotificationScheduler}가 같은 판단을 한 자리다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FormDraftRetentionScheduler {

    /**
     * 접수 종료 뒤 이만큼 지난 폼의 초안을 지운다.
     *
     * <p>90일은 #36의 결정값이고 알림 정리(읽은 지 90일)와 같은 길이다. 설정 손잡이로 빼지 않은 것은 이 값이 «운영 편의»가 아니라 <b>보관 근거의
     * 기한</b>이라 환경마다 달라지면 안 되기 때문이다 — dev에서 30일로 줄여 두면 그것이 곧 다른 정책이 된다.
     */
    private static final Duration DRAFT_RETENTION = Duration.ofDays(90);

    private final FormResponseHistoryRepository formResponseHistoryRepository;
    private final Clock clock;

    /**
     * 매주 월요일 05:00 KST.
     *
     * <p>04:00은 {@code DatabaseKeepAlive}, 04:30은 알림 정리다. 같은 시각에 겹치면 Supabase Free의 커넥션을 셋이 나눠 쓴다.
     */
    @Scheduled(cron = "0 0 5 * * MON", zone = "Asia/Seoul")
    public void purgeClosedFormDrafts() {
        try {
            purge();
        } catch (RuntimeException e) {
            log.error("closed form draft purge failed — 다음 주에 다시 돈다", e);
        }
    }

    /**
     * 실제 삭제. 테스트가 스케줄을 기다리지 않고 이것을 직접 부른다.
     *
     * @return 지운 초안 수
     */
    @Transactional
    public int purge() {
        Instant before = clock.instant().minus(DRAFT_RETENTION);
        int deleted =
                formResponseHistoryRepository.deleteDraftsOfFormsClosedBefore(
                        ResponseStatus.DRAFT, FormStatus.CLOSED, before);
        if (deleted > 0) {
            // 건수를 남기는 것은 처음 도는 날 «몇 건이 지워졌나»가 어디에도 없으면 되짚을 수
            // 없기 때문이다. 초안 내용·회원은 싣지 않는다(ADR-0024).
            log.info("closed form drafts purged: {} (기준 {})", deleted, before);
        }
        return deleted;
    }
}
