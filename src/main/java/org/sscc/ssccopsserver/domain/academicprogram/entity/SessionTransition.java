package org.sscc.ssccopsserver.domain.academicprogram.entity;

import java.util.EnumSet;
import java.util.Set;

/*
 * sesn_stts_cd 전이 액션 (#136 · POST .../sessions/{sessionId}/transitions).
 *
 * work 도메인의 TransitionAction·폼의 FormStatusAction·활동의 AcademicProgramTransition과 같은
 * 패턴이다 — 클라이언트는 다음 상태가 아니라 "무엇을 하겠다"를 보내고, 다음 상태는 이 표가
 * 정한다.
 *
 * 선행 상태가 둘 다 SUBMITTED 하나뿐인 것은 상태 모델(학술관리_데이터모델.md §3)이 그렇기
 * 때문이다 — REVISION_REQUESTED는 재제출(#135)로만 SUBMITTED로 돌아오고, **APPROVED 이후
 * 상태는 되돌리지 않는다**(승인된 회차 기록은 출석부·진행률 계산의 기준선이다). 그래서
 * '승인 취소' 액션이 여기 없고, 앞으로도 추가하려면 그 기준선을 어떻게 할지부터 정해야 한다.
 *
 * 남길 승인 이력의 상태(acdm_actv_aprv.aprv_stts_cd)를 액션이 함께 들고 있는 것은,
 * 이 대응(APPROVE→APPROVED · REQUEST_REVISION→REVISION_REQUESTED)이 전이표의 일부이기
 * 때문이다. 서비스에 switch로 두면 전이가 늘 때 고쳐야 할 자리가 두 곳이 된다.
 */
public enum SessionTransition {

    /** 승인 — SUBMITTED → APPROVED. 확정 이력이 되며 되돌리지 않는다 */
    APPROVE(
            SessionStatus.APPROVED,
            AcademicProgramApprovalStatus.APPROVED,
            EnumSet.of(SessionStatus.SUBMITTED),
            false),

    /** 수정요청 — SUBMITTED → REVISION_REQUESTED. 사유가 필수다(무엇을 고쳐야 하는지가 통보의 전부다) */
    REQUEST_REVISION(
            SessionStatus.REVISION_REQUESTED,
            AcademicProgramApprovalStatus.REVISION_REQUESTED,
            EnumSet.of(SessionStatus.SUBMITTED),
            true);

    private final SessionStatus targetStatus;
    private final AcademicProgramApprovalStatus approvalStatus;
    private final Set<SessionStatus> allowedFromStatuses;
    private final boolean reasonRequired;

    SessionTransition(
            SessionStatus targetStatus,
            AcademicProgramApprovalStatus approvalStatus,
            Set<SessionStatus> allowedFromStatuses,
            boolean reasonRequired) {
        this.targetStatus = targetStatus;
        this.approvalStatus = approvalStatus;
        this.allowedFromStatuses = allowedFromStatuses;
        this.reasonRequired = reasonRequired;
    }

    /** 이 전이가 현재 상태에서 허용되는가. 판단만 하고 오류는 던지지 않는다 — 던지는 자리는 SessionEntity다 */
    public boolean isAllowedFrom(SessionStatus currentStatus) {
        return allowedFromStatuses.contains(currentStatus);
    }

    public SessionStatus targetStatus() {
        return targetStatus;
    }

    /** 이 전이가 acdm_actv_aprv에 남길 처리 상태 */
    public AcademicProgramApprovalStatus approvalStatus() {
        return approvalStatus;
    }

    public boolean requiresReason() {
        return reasonRequired;
    }
}
