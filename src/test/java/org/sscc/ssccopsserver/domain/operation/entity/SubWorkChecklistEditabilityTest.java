package org.sscc.ssccopsserver.domain.operation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sscc.ssccopsserver.domain.operation.code.error.OperationErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

/*
 * 체크리스트에 걸리는 두 잠금을 나란히 놓고 본다 (#307 · ssccops#255 결정).
 *
 * requireChecklistEditable(체크·해제)는 완료만 막고, requireChecklistItemEditable
 * (항목 추가·문구 수정·삭제)는 검토부터 막는다. 두 기준을 같은 파일에서 보는 것은
 * 한쪽을 고치면서 다른 쪽을 따라 옮기는 일을 막기 위해서다 — 둘이 같아지는 순간 이 이슈가
 * 막으려던 것(심사 직전에 기준을 낮추는 경로)이 다시 생긴다.
 *
 * DB 없이 규칙만 본다 — 상위 업무·운영은 판정에 쓰이지 않으므로 넘기지 않는다.
 */
class SubWorkChecklistEditabilityTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final Instant COMPLETED_AT =
            OffsetDateTime.of(2026, 8, 20, 12, 0, 0, 0, KST).toInstant();

    // 기획(PLANNING) — 둘 다 열려 있다
    @Test
    void checklistItemsAreEditableWhilePlanning() {
        SubWorkEntity subWork = subWork();

        assertThat(subWork.getWorkStatus()).isEqualTo(WorkStatus.PLANNING);
        assertThat(subWork.isChecklistItemEditable()).isTrue();
        subWork.requireChecklistItemEditable();
        subWork.requireChecklistEditable();
    }

    // 진행(IN_PROGRESS) — 여전히 둘 다 열려 있다
    @Test
    void checklistItemsAreEditableWhileInProgress() {
        SubWorkEntity subWork = subWork();
        subWork.applyTransition(TransitionAction.START, null, true, 0, null);

        assertThat(subWork.isChecklistItemEditable()).isTrue();
        subWork.requireChecklistItemEditable();
    }

    /*
     * 검토(REVIEW) — **여기서 두 기준이 갈린다.** 체크는 여전히 되고(담당자가 남은
     * 항목을 마저 채운다) 항목 편집만 잠긴다 — 승인자가 보는 동안 목록이 움직이면 무엇을
     * 승인한 것인지 불분명해진다.
     */
    @Test
    void checklistItemsLockAtReviewWhileTogglingStaysOpen() {
        SubWorkEntity subWork = inReview();

        assertThat(subWork.getWorkStatus()).isEqualTo(WorkStatus.REVIEW);
        assertThat(subWork.isChecklistItemEditable()).isFalse();
        assertThatThrownBy(subWork::requireChecklistItemEditable)
                .isInstanceOf(GeneralException.class)
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.TRANSITION_NOT_ALLOWED);

        // 체크·해제는 그대로 된다 — 진척 기록과 조건 변경은 성격이 다르다
        subWork.requireChecklistEditable();
    }

    // 완료(DONE) — 둘 다 잠긴다
    @Test
    void everythingLocksWhenDone() {
        SubWorkEntity subWork = inReview();
        subWork.applyTransition(TransitionAction.APPROVE_COMPLETE, null, true, 0, COMPLETED_AT);

        assertThat(subWork.isChecklistItemEditable()).isFalse();
        assertThatThrownBy(subWork::requireChecklistItemEditable)
                .isInstanceOf(GeneralException.class);
        assertThatThrownBy(subWork::requireChecklistEditable).isInstanceOf(GeneralException.class);
    }

    /*
     * 반려로 진행에 되돌아오면 다시 열린다. 그것이 '계획을 고쳤 다시 올린다'의 뜻이고,
     * 새 승인 회차가 시작되는 자리와 같은 경계다.
     */
    @Test
    void checklistItemsReopenAfterRejection() {
        SubWorkEntity subWork = inReview();
        subWork.applyTransition(TransitionAction.REJECT, "현장 답사 결과 누락", true, 0, null);

        assertThat(subWork.getWorkStatus()).isEqualTo(WorkStatus.IN_PROGRESS);
        assertThat(subWork.isChecklistItemEditable()).isTrue();
        subWork.requireChecklistItemEditable();
    }

    private SubWorkEntity subWork() {
        SubWorkTypeEntity subWorkType =
                SubWorkTypeEntity.create("내부행사", false, null, false, null, List.of("장소 확정"));
        return SubWorkEntity.create(null, null, subWorkType, "봄MT 장소 선정", null, null, null);
    }

    private SubWorkEntity inReview() {
        SubWorkEntity subWork = subWork();
        subWork.applyTransition(TransitionAction.START, null, true, 0, null);
        subWork.applyTransition(TransitionAction.REQUEST_REVIEW, null, true, 0, null);
        return subWork;
    }
}
