package org.sscc.ssccopsserver.domain.academicprogram.entity;

/*
 * session_stts_cd — 회차 실적 상태 (학술관리_데이터모델.md §3). session 행에 문자열로 저장될
 * 값이지만, 엔티티(SessionEntity)는 아직 없다(#135) — 이 enum이 먼저 서 있는 것은 계획 조회
 * (#134)가 실적 없는 항목에 NOT_SUBMITTED를 합성해 내려야 하고, isEditable이 "어떤 상태에서
 * 기록을 쓸 수 있는가"를 물어야 하기 때문이다. 그 어휘를 DTO에 문자열 리터럴로 흩뿌리면
 * #135가 엔티티를 붙일 때 두 벌이 된다.
 *
 * NOT_SUBMITTED는 session 행이 아예 없는 상태를 가리키는 파생 값이다 — 빈 행을 미리 만들어
 * 두지 않는 것은 "아직 아무도 손대지 않은 계획"이 훨씬 흔한 상태이기 때문이며(데이터모델
 * §3), 그래서 이 값만은 DB에 저장되지 않고 서버가 응답에서 합성한다.
 */
public enum SessionStatus {
    NOT_SUBMITTED,
    SUBMITTED,
    APPROVED,
    REVISION_REQUESTED;

    /*
     * 스터디장/팀장이 지금 이 회차의 기록을 쓸 수 있는 상태인가. 미제출이면 신규 제출,
     * 수정요청이면 재제출이다 — SUBMITTED(국장 검토 대기)·APPROVED(확정 이력)는 손대지
     * 않는다. 제출·재제출 API(#135)도 이 판정을 다시 구현하지 말고 여기를 부를 것.
     */
    public boolean allowsRecording() {
        return this == NOT_SUBMITTED || this == REVISION_REQUESTED;
    }
}
