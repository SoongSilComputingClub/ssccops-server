package org.sscc.ssccopsserver.domain.academicprogram.entity;

import java.util.EnumSet;
import java.util.Set;

/*
 * acdm_actv_stts_cd 전이 액션 (#133 · POST /v1/academic-programs/{id}/transitions).
 *
 * `APPROVE`/`REJECT`/`REQUEST_REVISION` 3종은 없다 — 승인은 #150(승인 이관)이 대체하고
 * 반려·수정요청은 #141의 폼 응답 상태로 대체됐다(2026-08-24 재설계, 이슈 코멘트). 학술국장이
 * 직접 부르는 전이는 START_RECRUITMENT·APPROVE_COMPLETION 2종뿐이다.
 *
 * work 도메인의 TransitionAction·form 도메인의 FormStatusAction과 같은 패턴이다 — 클라이언트는
 * 다음 상태가 아니라 "무엇을 하겠다"를 보내고, 다음 상태는 이 표가 정한다.
 */
public enum AcademicProgramTransition {

    /** 모집 시작 — APPROVED → ONGOING. 연결된 Form을 OPEN 전이하고 모집 기간을 반영한다 */
    START_RECRUITMENT(AcademicProgramStatus.ONGOING, EnumSet.of(AcademicProgramStatus.APPROVED)),

    /** 종료/수료 승인 — ONGOING → COMPLETED. 진행률 미달이어도 자동 차단하지 않는다(학술국장 재량) */
    APPROVE_COMPLETION(AcademicProgramStatus.COMPLETED, EnumSet.of(AcademicProgramStatus.ONGOING));

    private final AcademicProgramStatus targetStatus;
    private final Set<AcademicProgramStatus> allowedFromStatuses;

    AcademicProgramTransition(
            AcademicProgramStatus targetStatus, Set<AcademicProgramStatus> allowedFromStatuses) {
        this.targetStatus = targetStatus;
        this.allowedFromStatuses = allowedFromStatuses;
    }

    /** 이 전이가 현재 상태에서 허용되는가. 판단만 하고 오류는 던지지 않는다 — 던지는 자리는 AcademicProgramEntity다 */
    public boolean isAllowedFrom(AcademicProgramStatus currentStatus) {
        return allowedFromStatuses.contains(currentStatus);
    }

    public AcademicProgramStatus targetStatus() {
        return targetStatus;
    }
}
