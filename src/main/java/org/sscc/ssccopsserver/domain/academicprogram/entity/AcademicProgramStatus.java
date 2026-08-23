package org.sscc.ssccopsserver.domain.academicprogram.entity;

/*
 * academic_program_stts_cd — 기획안 → 활동 진행 상태 (#131, 학술관리_데이터모델.md §3).
 * academic_program.academic_program_stts_cd에 문자열로 저장된다.
 *
 * 전이표(PROPOSED → REVISION_REQUESTED/REJECTED/APPROVED → ONGOING → COMPLETED)와 그 검증은
 * 이 이슈(#131) 범위 밖이다 — 생성 직후 PROPOSED로 고정하는 팩토리(AcademicProgramEntity.create)
 * 뿐이고, 실제 전이는 국장 승인 API(#133)의 몫이다. REJECTED는 종결 상태라 되살리지 않는다.
 */
public enum AcademicProgramStatus {
    PROPOSED,
    REVISION_REQUESTED,
    REJECTED,
    APPROVED,
    ONGOING,
    COMPLETED
}
