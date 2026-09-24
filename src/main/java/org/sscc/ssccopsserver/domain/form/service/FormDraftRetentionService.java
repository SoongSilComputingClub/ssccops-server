package org.sscc.ssccopsserver.domain.form.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.repository.FormResponseHistoryRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 접수가 끝난 폼에 남은 미제출 초안을 지운다 (#557 · ssccops#502 · #36 결정).
 *
 * <p><b>왜 스케줄러와 다른 빈인가 — 처음에는 같은 클래스였고, 그래서 트랜잭션이 걸리지 않았다</b> (#570). {@code @Scheduled} 메서드가 같은
 * 클래스의 {@code @Transactional} 메서드를 {@code this}로 부르면 Spring 프록시를 지나지 않아 <b>애노테이션이 아무 일도 하지 않는다.</b>
 * 아래 삭제는 {@code @Modifying} 벌크 질의라 트랜잭션이 없으면 {@code TransactionRequiredException}으로 죽는데, 그 예외를
 * 스케줄러의 {@code catch}가 받아 <b>주 1회 로그 한 줄로만 남는다</b> — 정책이 실행되지 않는 채로 조용히 지나갈 자리였다.
 *
 * <p>같은 도메인의 {@code DeadlineNotificationScheduler}는 처음부터 이 모양이다(스케줄러가 서비스 빈을 주입받아 부른다). 그쪽과 같게 맞춘
 * 것이지 새 구조를 들인 것이 아니다.
 *
 * <p><b>테스트가 못 잡은 이유도 적어 둔다.</b> {@code FormDraftRetentionQueryTest}는 {@code @DataJpaTest}라 <b>테스트
 * 자체가 트랜잭션 안에서 돈다</b> — 질의는 정상으로 보인다. 빠진 것은 질의가 아니라 배선이었고, 그것은 질의 테스트가 볼 수 있는 종류가 아니다. Sonar의
 * {@code java:S2229}·{@code java:S6809}가 잡았다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FormDraftRetentionService {

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
     * 실제 삭제.
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
