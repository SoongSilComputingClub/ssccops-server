package org.sscc.ssccopsserver.domain.academicprogram.entity;

/*
 * academic_program_aprv.aprv_stts_cd (#133, 학술관리_데이터모델.md §2).
 *
 * PENDING은 aprv_dt가 NULL인 처리 대기 행을 뜻한다 — 회차 승인(#136)이 제출 시점에 이 상태로
 * 행을 만든다. 이 이슈(#133)의 APPROVE_COMPLETION은 대기 없이 곧바로 APPROVED로 기록한다.
 */
public enum AcademicProgramApprovalStatus {
    PENDING,
    APPROVED,
    REVISION_REQUESTED,
    REJECTED
}
