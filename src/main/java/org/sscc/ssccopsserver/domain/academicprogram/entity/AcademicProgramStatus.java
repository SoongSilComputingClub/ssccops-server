package org.sscc.ssccopsserver.domain.academicprogram.entity;

/*
 * academic_program_stts_cd — 승인 이후 활동 진행 상태 (#131 → #133, 학술관리_데이터모델.md §3,
 * 2026-08-24 재설계). academic_program.academic_program_stts_cd에 문자열로 저장된다.
 *
 * `PROPOSED`/`REVISION_REQUESTED`/`REJECTED`는 없다 — 기획안 접수·검토·반려·수정요청은 전부
 * 폼 도메인(form_rspns_hstry, #141)의 상태이지 이 엔티티의 상태가 아니다. 반려된 기획안은
 * AcademicProgram 행 자체가 만들어지지 않으므로(폼 응답 단계에서 끝난다) 승인이 곧 생성이고,
 * 행은 항상 APPROVED로 태어난다(AcademicProgramEntity.create).
 *
 * 전이표(APPROVED → ONGOING → COMPLETED)와 그 검증은 AcademicProgramTransition·
 * AcademicProgramEntity.changeStatus가 갖는다(#133).
 */
public enum AcademicProgramStatus {
    APPROVED,
    ONGOING,
    COMPLETED
}
