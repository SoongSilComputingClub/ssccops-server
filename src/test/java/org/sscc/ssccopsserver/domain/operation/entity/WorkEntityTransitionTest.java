package org.sscc.ssccopsserver.domain.operation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.sscc.ssccopsserver.domain.operation.code.error.OperationErrorCode;
import org.sscc.ssccopsserver.global.apipayload.exception.GeneralException;

/*
 * 상위 업무 전이표 (#622 · ssccops#563). 표에 있는 다섯 줄은 통과하고, 그 밖의 (상태, 전이) 조합은
 * 전부 TRANSITION_NOT_ALLOWED다 — 4 상태 × 5 전이 = 20칸 중 5칸만 열린다.
 */
class WorkEntityTransitionTest {

    @ParameterizedTest(name = "{0} --{1}--> {2}")
    @CsvSource({
        "PLANNING, START, IN_PROGRESS",
        "IN_PROGRESS, REQUEST_REVIEW, REVIEW",
        "REVIEW, COMPLETE, DONE",
        "REVIEW, REVERT_REVIEW, IN_PROGRESS",
        "DONE, REOPEN, IN_PROGRESS"
    })
    void transitionTableMovesStatus(
            WorkStatus from, WorkTransitionAction action, WorkStatus expected) {
        WorkEntity work = workIn(from);

        work.applyTransition(action, 0);

        assertThat(work.getWorkStatus()).isEqualTo(expected);
    }

    // 표에 없는 15칸 — 상태는 그대로 남는다
    @Test
    void everyOtherCombinationIsRejectedAndKeepsStatus() {
        int rejected = 0;
        for (WorkStatus from : WorkStatus.values()) {
            for (WorkTransitionAction action : WorkTransitionAction.values()) {
                if (allowedFrom(action) == from) {
                    continue;
                }
                WorkEntity work = workIn(from);
                assertThatThrownBy(() -> work.applyTransition(action, 0))
                        .isInstanceOf(GeneralException.class)
                        .extracting(ex -> ((GeneralException) ex).getErrorCode())
                        .isEqualTo(OperationErrorCode.TRANSITION_NOT_ALLOWED);
                assertThat(work.getWorkStatus()).isEqualTo(from);
                rejected++;
            }
        }
        assertThat(rejected).isEqualTo(15);
    }

    // 남은 하위 업무가 있으면 완료가 막히고, 메시지에 남은 수가 실린다
    @Test
    void completeIsBlockedWhileSubWorksRemain() {
        WorkEntity work = workIn(WorkStatus.REVIEW);

        assertThatThrownBy(() -> work.applyTransition(WorkTransitionAction.COMPLETE, 2))
                .isInstanceOf(GeneralException.class)
                .hasMessage("완료되지 않은 하위 업무가 2건 남아 있습니다.")
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.SUB_WORK_UNFINISHED);
        assertThat(work.getWorkStatus()).isEqualTo(WorkStatus.REVIEW);
    }

    // 검토가 아닌 업무의 완료는 남은 수보다 순서 위반이 먼저다
    @Test
    void orderViolationWinsOverRemainingSubWorks() {
        WorkEntity work = workIn(WorkStatus.IN_PROGRESS);

        assertThatThrownBy(() -> work.applyTransition(WorkTransitionAction.COMPLETE, 3))
                .extracting(ex -> ((GeneralException) ex).getErrorCode())
                .isEqualTo(OperationErrorCode.TRANSITION_NOT_ALLOWED);
    }

    private static WorkStatus allowedFrom(WorkTransitionAction action) {
        return switch (action) {
            case START -> WorkStatus.PLANNING;
            case REQUEST_REVIEW -> WorkStatus.IN_PROGRESS;
            case COMPLETE, REVERT_REVIEW -> WorkStatus.REVIEW;
            case REOPEN -> WorkStatus.DONE;
        };
    }

    // 생성은 언제나 기획이므로 표의 정방향 전이로 원하는 상태까지 옮긴다
    private static WorkEntity workIn(WorkStatus status) {
        WorkEntity work = WorkEntity.create(null, WorkType.EVENT, null);
        if (status == WorkStatus.PLANNING) {
            return work;
        }
        work.applyTransition(WorkTransitionAction.START, 0);
        if (status == WorkStatus.IN_PROGRESS) {
            return work;
        }
        work.applyTransition(WorkTransitionAction.REQUEST_REVIEW, 0);
        if (status == WorkStatus.REVIEW) {
            return work;
        }
        work.applyTransition(WorkTransitionAction.COMPLETE, 0);
        return work;
    }
}
