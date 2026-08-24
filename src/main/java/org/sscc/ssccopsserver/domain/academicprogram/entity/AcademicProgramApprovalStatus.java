package org.sscc.ssccopsserver.domain.academicprogram.entity;

/*
 * academic_program_aprv.aprv_stts_cd (#133, 학술관리_데이터모델.md §2).
 *
 * PENDING은 aprv_dt가 NULL인 처리 대기 행을 뜻한다. **지금 이 값을 쓰는 경로는 없다** —
 * #133의 APPROVE_COMPLETION도, #136의 회차 승인·수정요청도 대기 없이 곧바로 결정을 담아
 * 기록한다.
 *
 * 회차 제출(#135) 시점에 PENDING 행을 깔아 두지 않는 것은 "검토를 기다린다"는 사실이 이미
 * session_stts_cd = SUBMITTED에 있기 때문이다. 같은 사실을 두 테이블에 적으면 재제출마다
 * 둘을 함께 맞춰야 하고, 어긋나는 순간 승인 대기 목록(#136 reviews/sessions)과 회차 상태가
 * 다른 답을 내놓는다. 이 테이블에 남는 것은 대기가 아니라 **처리**다.
 */
public enum AcademicProgramApprovalStatus {
    PENDING,
    APPROVED,
    REVISION_REQUESTED,
    REJECTED
}
