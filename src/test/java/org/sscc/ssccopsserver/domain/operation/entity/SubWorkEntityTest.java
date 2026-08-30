package org.sscc.ssccopsserver.domain.operation.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

/*
 * 지연 판정(isDelayedBefore)만 다룬다. "마감이 지났어도 완료된 건은 지연이 아니다"가 여기의
 * 규칙이고, 그 규칙이 어긋나 완료 업무가 '지연'으로 표기된 것이 ssccops#112다 (#194).
 *
 * 같은 규칙이 목록 필터(SubWorkRepositoryImpl의 overdueOnly)에 SQL로 한 벌 더 적혀 있어
 * 한쪽만 고치면 단건과 목록이 갈린다. 그래서 같은 네 케이스를 목록 쪽에서도 나란히 확인한다
 * — SubWorkServiceImplSearchTest.singleJudgementAndOverdueFilterAgreeOnEveryCase.
 *
 * 경계 시각은 '지금'이 아니라 서비스 표준 시간대의 오늘 0시다 (DeadlinePolicy, #121).
 * 그래서 마감일이 오늘인 건은 마감 시각이 이미 지났어도 아직 지연이 아니다.
 */
class SubWorkEntityTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    // DeadlinePolicy.overdueBefore가 주는 값 — 2026-08-20(KST) 0시
    private static final Instant OVERDUE_BEFORE =
            OffsetDateTime.of(2026, 8, 20, 0, 0, 0, 0, KST).toInstant();

    private static final OffsetDateTime PAST_DUE = OffsetDateTime.of(2026, 8, 15, 18, 0, 0, 0, KST);
    private static final OffsetDateTime FUTURE_DUE =
            OffsetDateTime.of(2026, 8, 25, 18, 0, 0, 0, KST);

    // 마감일은 오늘인데 마감 시각은 이미 지난 건. 초 단위로 재던 시절 지연으로 뒤집히던 자리다
    private static final OffsetDateTime DUE_TODAY_MORNING =
            OffsetDateTime.of(2026, 8, 20, 9, 0, 0, 0, KST);

    // 경계 바로 앞 — 여기부터가 지연이다
    private static final OffsetDateTime DUE_YESTERDAY_MIDNIGHT =
            OffsetDateTime.of(2026, 8, 19, 23, 59, 59, 0, KST);

    /*
     * 완료된 건은 늦게 끝났더라도 지연이 아니다 (ssccops#112의 본체). 화면이 이 값으로
     * '지금 손봐야 하는 건'을 표시하므로, 이미 끝난 일이 여기에 섞이면 목록의 뜻이 무너진다.
     */
    @Test
    void completedSubWorkIsNotDelayedEvenWhenDueAtHasPassed() {
        SubWorkEntity subWork = completed(PAST_DUE);

        assertThat(subWork.getWorkStatus()).isEqualTo(WorkStatus.DONE);
        assertThat(subWork.isDelayedBefore(OVERDUE_BEFORE)).isFalse();
    }

    @Test
    void unfinishedSubWorkPastDueIsDelayed() {
        assertThat(subWork(PAST_DUE).isDelayedBefore(OVERDUE_BEFORE)).isTrue();
    }

    @Test
    void unfinishedSubWorkNotYetDueIsNotDelayed() {
        assertThat(subWork(FUTURE_DUE).isDelayedBefore(OVERDUE_BEFORE)).isFalse();
    }

    // 마감이 없는 하위 업무는 지연될 수 없다 — 완료 여부와 무관하다
    @Test
    void subWorkWithoutDueAtIsNeverDelayed() {
        assertThat(subWork(null).isDelayedBefore(OVERDUE_BEFORE)).isFalse();
        assertThat(completed(null).isDelayedBefore(OVERDUE_BEFORE)).isFalse();
    }

    /*
     * 판정 단위는 시각이 아니라 일자다 (#121). 마감일 당일은 지연이 아니고 다음 날 0시부터
     * 지연이다 — 화면의 D-day가 날짜 단위라 서버만 초 단위로 재면 같은 건이 '지연'과
     * 'D-DAY'로 동시에 표시된다.
     */
    @Test
    void subWorkDueTodayIsNotDelayedUntilTheDayIsOver() {
        assertThat(subWork(DUE_TODAY_MORNING).isDelayedBefore(OVERDUE_BEFORE)).isFalse();
        assertThat(subWork(DUE_YESTERDAY_MIDNIGHT).isDelayedBefore(OVERDUE_BEFORE)).isTrue();
    }

    /*
     * 판정은 dly_yn 컬럼을 읽지 않는다 (#117). 그 컬럼은 등록 시 false로 굳고 갱신하는 주체가
     * 없어, 컬럼을 읽는 순간 지연된 건이 하나도 잡히지 않는다.
     */
    @Test
    void judgementDoesNotReadTheDelayedColumn() {
        SubWorkEntity subWork = subWork(PAST_DUE);

        assertThat(subWork.isDelayed()).isFalse();
        assertThat(subWork.isDelayedBefore(OVERDUE_BEFORE)).isTrue();
    }

    /*
     * 승인이 필요 없는 유형이라 정족수·승인자 없이 완료까지 갈 수 있다 (REQ-016). 상위 업무·
     * 운영은 판정에 쓰이지 않으므로 넘기지 않는다 — 이 테스트는 DB 없이 규칙만 본다.
     */
    private SubWorkEntity subWork(OffsetDateTime dueAt) {
        SubWorkTypeEntity subWorkType =
                SubWorkTypeEntity.create("내부행사", false, null, false, null, List.of("장소 확정"));
        return SubWorkEntity.create(
                null,
                null,
                subWorkType,
                "봄MT 장소 선정",
                null,
                null,
                dueAt == null ? null : dueAt.toInstant());
    }

    // 정상 경로로 완료까지 올린다 (TR-01 → TR-02 → TR-03). 상태를 직접 쓰는 setter가 없다
    private SubWorkEntity completed(OffsetDateTime dueAt) {
        SubWorkEntity subWork = subWork(dueAt);
        subWork.applyTransition(TransitionAction.START, null, true, 0, null);
        subWork.applyTransition(TransitionAction.REQUEST_REVIEW, null, true, 0, null);
        subWork.applyTransition(TransitionAction.APPROVE_COMPLETE, null, true, 0, OVERDUE_BEFORE);
        return subWork;
    }
}
