package org.sscc.ssccopsserver.domain.academicprogram.entity;

import java.util.EnumSet;
import java.util.Set;

/*
 * acdm_actv_stts_cd 전이 액션 (#133 · POST /v1/academic-programs/{id}/transitions).
 *
 * `APPROVE`/`REJECT`/`REQUEST_REVISION` 3종은 없다 — 승인은 #150(승인 이관)이 대체하고
 * 반려·수정요청은 #141의 폼 응답 상태로 대체됐다(2026-08-24 재설계, 이슈 코멘트). 학술국장이
 * 직접 부르는 전이는 셋이다.
 *
 *   현재 \ 액션 | START_RECRUITMENT | APPROVE_COMPLETION | REOPEN
 *   APPROVED    | → ONGOING         | ✕                  | ✕
 *   ONGOING     | ✕                 | → COMPLETED        | ✕
 *   COMPLETED   | ✕                 | ✕                  | → ONGOING
 *
 * **종료는 그 활동의 쓰기를 전부 멈춘다**(#597 · ADR-0057) — 판정은 AcademicProgramStatus.
 * acceptsWrites, 거절은 AcademicProgramWritePolicy다. 그래서 잘못 누른 종료를 되돌릴 길이
 * 필요했고 그것이 REOPEN이다. REOPEN이 APPROVED가 아니라 ONGOING으로 가는 것은 모집이 이미
 * 시작됐던 사실까지 되돌리지는 않기 때문이다(hasStartedRecruitment).
 *
 * work 도메인의 TransitionAction·form 도메인의 FormStatusAction과 같은 패턴이다 — 클라이언트는
 * 다음 상태가 아니라 "무엇을 하겠다"를 보내고, 다음 상태는 이 표가 정한다.
 */
public enum AcademicProgramTransition {

    /** 모집 시작 — APPROVED → ONGOING. 연결된 Form을 OPEN 전이하고 모집 기간을 반영한다 */
    START_RECRUITMENT(AcademicProgramStatus.ONGOING, EnumSet.of(AcademicProgramStatus.APPROVED)),

    /*
     * 종료/수료 승인 — ONGOING → COMPLETED. 진행률 미달이어도 자동 차단하지 않는다(학술국장 재량).
     * 연결된 모집 폼이 접수 중(OPEN)이면 같은 트랜잭션에서 마감한다(#597)
     */
    APPROVE_COMPLETION(AcademicProgramStatus.COMPLETED, EnumSet.of(AcademicProgramStatus.ONGOING)),

    /*
     * 재시작 — COMPLETED → ONGOING (#597 · ADR-0057). 모집 폼은 다시 열지 않는다 — 모집은 폼
     * 화면에서 따로 연다. 종료 때 마감한 폼을 여기서 되살리면 종료 전에 이미 닫혀 있던 폼까지
     * 열리고, 둘을 가르려면 «누가 닫았나»를 어딘가에 따로 적어야 한다
     */
    REOPEN(AcademicProgramStatus.ONGOING, EnumSet.of(AcademicProgramStatus.COMPLETED));

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
