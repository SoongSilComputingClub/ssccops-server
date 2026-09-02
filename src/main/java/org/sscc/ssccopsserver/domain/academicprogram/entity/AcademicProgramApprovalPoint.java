package org.sscc.ssccopsserver.domain.academicprogram.entity;

/*
 * acdm_actv_aprv.acdm_actv_aprv_se_cd — 무엇에 대한 승인인지 (#133, 학술관리_데이터모델.md §2).
 *
 * `PROPOSAL`은 없다 — 기획안 승인은 폼 응답 검토(form_rspns_rvw_hstry, #141)가 정본이라 이
 * 테이블에 다시 남기지 않는다(2026-08-24 재설계, 이 이슈 코멘트). SESSION(회차 승인)은 #136,
 * COMPLETION(종료/수료 승인)은 이 이슈(#133)의 APPROVE_COMPLETION 전이가 쓴다.
 */
public enum AcademicProgramApprovalPoint {
    SESSION,
    COMPLETION
}
