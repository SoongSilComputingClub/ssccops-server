package org.sscc.ssccopsserver.domain.operation.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

/*
 * 정체 판정 두 규칙의 단위 검증 (ssccops#196). 지연(isDelayedBefore)과 파일을 나눈 것은
 * 규칙이 다르기 때문이 아니라 다음에 손댈 사람이 다르기 때문이다 — 지연은 마감의 문제이고
 * 정체는 절차의 문제다.
 *
 *   ① isReadyForReview   완료 점검 전부 체크 + 아직 검토요청 전   → 담당자가 누를 것
 *   ② isReviewStaleBefore 검토요청 후 3일 경과, 아직 검토 상태     → 승인자가 누를 것
 *
 * 같은 규칙이 목록 필터(SubWorkRepositoryImpl)에 SQL로 한 벌 더 적혀 있어 한쪽만 고치면
 * 단건과 목록이 갈린다 — SubWorkServiceImplSearchTest.stallJudgementAndFiltersAgreeOnEveryCase가
 * 두 경로를 나란히 확인한다. 지연 판정이 그 자리에서 두 번 갈렸다 (#121 · #194).
 */
class SubWorkStallEntityTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    /*
     * DeadlinePolicy.reviewStaleBefore가 주는 값. 오늘이 2026-08-20(KST)이면 경계는
     * 2026-08-18 0시다 — 요청일 + 3일 ≤ 오늘이 되는 첫 지점이다(18일에 요청했으면 20일이
     * 3일째다). 임계값 자체의 검증은 DeadlinePolicyTest가 맡고, 여기서는 그 값을 받아
     * 쓰는 쪽만 본다.
     */
    private static final Instant REVIEW_STALE_BEFORE =
            OffsetDateTime.of(2026, 8, 18, 0, 0, 0, 0, KST).toInstant();

    // 경계보다 하루 앞 — 3일이 지났다
    private static final Instant REQUESTED_THREE_DAYS_AGO =
            OffsetDateTime.of(2026, 8, 17, 23, 0, 0, 0, KST).toInstant();

    // 경계 바로 뒤 — 아직 2일째다. 이 한 건이 "3일부터"를 못 박는다
    private static final Instant REQUESTED_TWO_DAYS_AGO =
            OffsetDateTime.of(2026, 8, 18, 9, 0, 0, 0, KST).toInstant();

    // ─── ① 검토요청 전 ───────────────────────────────────────────────

    // 점검을 다 채웠는데 아직 검토요청을 안 눌렀다. 이 상태가 이 이슈가 드러내려는 것이다
    @Test
    void allCheckedButNotYetRequestedIsReadyForReview() {
        assertThat(planning().isReadyForReview(2, 2)).isTrue();
        assertThat(started().isReadyForReview(2, 2)).isTrue();
    }

    // 남은 항목이 있으면 아직 할 일이 남은 것이지 정체가 아니다
    @Test
    void partiallyCheckedIsNotReadyForReview() {
        assertThat(started().isReadyForReview(1, 2)).isFalse();
        assertThat(started().isReadyForReview(0, 2)).isFalse();
    }

    /*
     * 점검 항목이 하나도 없는 유형은 해당하지 않는다. 0 == 0이라 "전부 체크"가 공허하게
     * 참이 되는데, 그대로 두면 항목 없는 유형의 모든 건이 **등록 직후부터** 정체로 찍힌다.
     */
    @Test
    void typeWithoutChecklistIsNeverReadyForReview() {
        assertThat(planning().isReadyForReview(0, 0)).isFalse();
        assertThat(started().isReadyForReview(0, 0)).isFalse();
    }

    /*
     * 검토·완료는 이미 요청을 누른 뒤다. 검토 상태까지 ①에 넣으면 승인자를 기다리는 건이
     * 담당자 몫으로 잘못 표시되고, ②와도 겹쳐 한 건이 두 칩에 동시에 잡힌다.
     */
    @Test
    void alreadyRequestedOrDoneIsNotReadyForReview() {
        assertThat(inReview().isReadyForReview(2, 2)).isFalse();
        assertThat(completed().isReadyForReview(2, 2)).isFalse();
    }

    // ─── ② 승인 대기 3일 ─────────────────────────────────────────────

    // 3일이 지난 검토 대기 건. 다음에 누를 사람은 승인자다
    @Test
    void reviewRequestedThreeDaysAgoIsStale() {
        assertThat(inReview().isReviewStaleBefore(REQUESTED_THREE_DAYS_AGO, REVIEW_STALE_BEFORE))
                .isTrue();
    }

    // 2일째는 아직 아니다 — 경계가 하루 앞으로 밀리면 승인자가 볼 카드가 하루 일찍 쌓인다
    @Test
    void reviewRequestedTwoDaysAgoIsNotStaleYet() {
        assertThat(inReview().isReviewStaleBefore(REQUESTED_TWO_DAYS_AGO, REVIEW_STALE_BEFORE))
                .isFalse();
    }

    /*
     * 검토 상태가 아니면 대기 중인 요청이 없다. 반려돼 진행으로 돌아간 건은 이력에 옛
     * 검토요청 시각이 남아 있어, 상태를 보지 않으면 담당자가 다시 올리지도 않았는데
     * 승인자에게 정체로 보인다.
     */
    @Test
    void subWorkNotInReviewIsNeverStale() {
        assertThat(started().isReviewStaleBefore(REQUESTED_THREE_DAYS_AGO, REVIEW_STALE_BEFORE))
                .isFalse();
        assertThat(completed().isReviewStaleBefore(REQUESTED_THREE_DAYS_AGO, REVIEW_STALE_BEFORE))
                .isFalse();
    }

    // 검토요청 이력이 없으면 판정할 근거가 없다 (이관 데이터 대비)
    @Test
    void missingRequestTimeIsNotStale() {
        assertThat(inReview().isReviewStaleBefore(null, REVIEW_STALE_BEFORE)).isFalse();
    }

    /*
     * 완료된 건은 어느 쪽도 아니다 — 지연 판정과 같은 이유다. 화면이 이 값들로 '지금
     * 손봐야 하는 건'을 표시하므로 이미 끝난 일이 섞이면 목록의 뜻이 무너진다.
     */
    @Test
    void completedSubWorkIsNeitherKindOfStall() {
        SubWorkEntity subWork = completed();
        assertThat(subWork.isReadyForReview(2, 2)).isFalse();
        assertThat(subWork.isReviewStaleBefore(REQUESTED_THREE_DAYS_AGO, REVIEW_STALE_BEFORE))
                .isFalse();
    }

    /*
     * 승인이 필요 없는 유형이라 정족수·승인자 없이 완료까지 갈 수 있다 (REQ-016). 상위 업무·
     * 운영은 판정에 쓰이지 않으므로 넘기지 않는다 — 이 테스트는 DB 없이 규칙만 본다.
     */
    private SubWorkEntity planning() {
        SubWorkTypeEntity subWorkType =
                SubWorkTypeEntity.create("내부행사", false, null, false, null, List.of("장소 확정"));
        return SubWorkEntity.create(null, null, subWorkType, "봄MT 장소 선정", null, null, null);
    }

    private SubWorkEntity started() {
        SubWorkEntity subWork = planning();
        subWork.applyTransition(TransitionAction.START, null, true, 0, null);
        return subWork;
    }

    private SubWorkEntity inReview() {
        SubWorkEntity subWork = started();
        subWork.applyTransition(TransitionAction.REQUEST_REVIEW, null, true, 0, null);
        return subWork;
    }

    // 정상 경로로 완료까지 올린다 (TR-01 → TR-02 → TR-03). 상태를 직접 쓰는 setter가 없다
    private SubWorkEntity completed() {
        SubWorkEntity subWork = inReview();
        subWork.applyTransition(
                TransitionAction.APPROVE_COMPLETE, null, true, 0, REVIEW_STALE_BEFORE);
        return subWork;
    }
}
