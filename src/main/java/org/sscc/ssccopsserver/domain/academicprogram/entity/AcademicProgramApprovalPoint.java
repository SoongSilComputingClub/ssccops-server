package org.sscc.ssccopsserver.domain.academicprogram.entity;

/*
 * acdm_actv_aprv.acdm_actv_aprv_se_cd — 무엇에 대한 승인인지 (#133, 학술관리_데이터모델.md §2).
 *
 * `PROPOSAL`은 없다 — 기획안 승인은 폼 응답 검토(form_rspns_rvw_hstry, #141)가 정본이라 이
 * 테이블에 다시 남기지 않는다(2026-08-24 재설계, 이 이슈 코멘트). SESSION(회차 승인)은 #136,
 * COMPLETION(종료/수료 승인)은 이 이슈(#133)의 APPROVE_COMPLETION 전이가 쓴다.
 *
 * REOPEN(재시작, #597 · ADR-0057)은 «승인»이 아니라 종료를 되돌린 **처리**다. 그래도 이 테이블에
 * 두는 것은 «누가 언제 다시 열었나»가 화면에서 보여야 하는데(승인 이력 조회 #139) 감사 로그는
 * 읽어 오는 API가 없기 때문이다. 종료 줄은 지우지 않고 재시작 줄을 덧붙인다 — 종료·재시작을
 * 반복한 사실도 기록이다.
 *
 * 값을 늘리면 acdm_actv_aprv_se_cd CHECK 제약도 새 마이그레이션으로 넓힌다(V26) —
 * FlywayMigrationValidateTest.checkConstraintsMatchTheirEnums가 둘을 대조한다.
 */
public enum AcademicProgramApprovalPoint {
    SESSION,
    COMPLETION,
    REOPEN
}
