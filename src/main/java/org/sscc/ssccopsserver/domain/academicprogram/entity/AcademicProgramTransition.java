package org.sscc.ssccopsserver.domain.academicprogram.entity;

import java.util.EnumSet;
import java.util.Set;

/*
 * acdm_actv_stts_cd 전이 액션 (#133 · POST /v1/academic-programs/{id}/transitions).
 *
 * `APPROVE`/`REJECT`/`REQUEST_REVISION` 3종은 없다 — 승인은 #150(승인 이관)이 대체하고
 * 반려·수정요청은 #141의 폼 응답 상태로 대체됐다(2026-08-24 재설계, 이슈 코멘트). 학술국장이
 * 직접 부르는 전이는 다섯이다.
 *
 *   현재 \ 액션   | START_RECRUITMENT | APPROVE_COMPLETION | REOPEN    | DISCONTINUE     | REINSTATE
 *   APPROVED      | → ONGOING         | ✕                  | ✕         | → DISCONTINUED  | ✕
 *   ONGOING       | ✕                 | → COMPLETED        | ✕         | → DISCONTINUED  | ✕
 *   COMPLETED     | ✕                 | ✕                  | → ONGOING | ✕               | ✕
 *   DISCONTINUED  | ✕                 | ✕                  | ✕         | ✕               | → 폐지 전 상태
 *
 * **종료와 폐지는 그 활동의 쓰기를 전부 멈춘다**(#597 · ADR-0057 · #611 · ADR-0058) — 판정은
 * AcademicProgramStatus.acceptsWrites, 거절은 AcademicProgramWritePolicy다. 그래서 잘못 누른
 * 것을 되돌릴 길이 각각 있고 그것이 REOPEN·REINSTATE다. REOPEN이 APPROVED가 아니라 ONGOING으로
 * 가는 것은 모집이 이미 시작됐던 사실까지 되돌리지는 않기 때문이다(hasStartedRecruitment).
 *
 * **종료에서는 폐지하지 않는다** — 끝난 것은 중단된 것이 아니다(ADR-0058). 종료를 잘못 눌렀다면
 * 재시작한 뒤 폐지한다.
 *
 * work 도메인의 TransitionAction·form 도메인의 FormStatusAction과 같은 패턴이다 — 클라이언트는
 * 다음 상태가 아니라 "무엇을 하겠다"를 보내고, 다음 상태는 이 표가 정한다. **REINSTATE 하나만
 * 예외다** — 폐지 전 상태가 승인일 수도 진행 중일 수도 있어 표가 아니라 폐지 이력
 * (acdm_actv_aprv.bfr_acdm_actv_stts_cd)이 정한다(AcademicProgramEntity.reinstate).
 */
public enum AcademicProgramTransition {

    /** 모집 시작 — APPROVED → ONGOING. 연결된 Form을 OPEN 전이하고 모집 기간을 반영한다 */
    START_RECRUITMENT(
            AcademicProgramStatus.ONGOING, EnumSet.of(AcademicProgramStatus.APPROVED), false),

    /*
     * 종료/수료 승인 — ONGOING → COMPLETED. 진행률 미달이어도 자동 차단하지 않는다(학술국장 재량).
     * 연결된 모집 폼이 접수 중(OPEN)이면 같은 트랜잭션에서 마감한다(#597)
     */
    APPROVE_COMPLETION(
            AcademicProgramStatus.COMPLETED, EnumSet.of(AcademicProgramStatus.ONGOING), false),

    /*
     * 재시작 — COMPLETED → ONGOING (#597 · ADR-0057). 모집 폼은 다시 열지 않는다 — 모집은 폼
     * 화면에서 따로 연다. 종료 때 마감한 폼을 여기서 되살리면 종료 전에 이미 닫혀 있던 폼까지
     * 열리고, 둘을 가르려면 «누가 닫았나»를 어딘가에 따로 적어야 한다
     */
    REOPEN(AcademicProgramStatus.ONGOING, EnumSet.of(AcademicProgramStatus.COMPLETED), false),

    /*
     * 폐지 — APPROVED | ONGOING → DISCONTINUED (#611 · ADR-0058). **사유가 필수다** — 왜 멈췄는지가
     * 이력(opnn_cn)에 남는 유일한 설명이다. 폐지 전 상태를 폐지 줄(bfr_acdm_actv_stts_cd)에 남기고,
     * 접수 중인 모집 폼은 같은 트랜잭션에서 마감한다(종료와 같은 자리). 팀원 명단은 건드리지
     * 않는다 — 참가 취소는 최종 상태라 전원 취소하면 복원해도 돌아오지 않는다
     */
    DISCONTINUE(
            AcademicProgramStatus.DISCONTINUED,
            EnumSet.of(AcademicProgramStatus.APPROVED, AcademicProgramStatus.ONGOING),
            true),

    /*
     * 복원 — DISCONTINUED → 폐지 전 상태 (#611 · ADR-0058). 목적 상태를 표가 갖지 않는다(위 주석).
     * 사유는 선택이다. 모집 폼은 다시 열지 않는다(REOPEN과 같은 이유). RESTORE로 짓지 않은 것은
     * 폼·행사 소프트 삭제의 되살리기(ADR-0053 · MCP restore_*)가 이미 쓰는 말이라서다
     */
    REINSTATE(null, EnumSet.of(AcademicProgramStatus.DISCONTINUED), false);

    private final AcademicProgramStatus targetStatus;
    private final Set<AcademicProgramStatus> allowedFromStatuses;
    private final boolean reasonRequired;

    AcademicProgramTransition(
            AcademicProgramStatus targetStatus,
            Set<AcademicProgramStatus> allowedFromStatuses,
            boolean reasonRequired) {
        this.targetStatus = targetStatus;
        this.allowedFromStatuses = allowedFromStatuses;
        this.reasonRequired = reasonRequired;
    }

    /** 이 전이가 현재 상태에서 허용되는가. 판단만 하고 오류는 던지지 않는다 — 던지는 자리는 AcademicProgramEntity다 */
    public boolean isAllowedFrom(AcademicProgramStatus currentStatus) {
        return allowedFromStatuses.contains(currentStatus);
    }

    /** 사유 없이는 성립하지 않는 전이인가 — 지금은 DISCONTINUE 하나다(SessionTransition.requiresReason과 같은 자리) */
    public boolean requiresReason() {
        return reasonRequired;
    }

    /** 이 표가 목적 상태를 정하는가. REINSTATE만 아니다 — 폐지 이력이 정한다 */
    public boolean hasFixedTarget() {
        return targetStatus != null;
    }

    /*
     * 목적 상태. REINSTATE에 부르면 IllegalStateException이다 — null을 돌려주면 부른 쪽이 상태를
     * null로 저장하고, 그 실패는 NOT NULL 제약이 커밋 때에야 알린다.
     */
    public AcademicProgramStatus targetStatus() {
        if (targetStatus == null) {
            throw new IllegalStateException(name() + "의 목적 상태는 이 표가 아니라 폐지 이력이 정한다");
        }
        return targetStatus;
    }
}
