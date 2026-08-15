package org.sscc.ssccopsserver.domain.operation.entity;

/*
 * 회의 상태 전이 액션 (OPS-026). API 정의서 02_상태_전이의 TR-M1~M4를 옮겼다.
 *
 * 정의서는 액션명을 한글(개회·회의록작성·종료·취소)로 적고 있으나, 하위 업무 전이
 * (TransitionAction)와 같은 이유로 저장·전송 값은 영문 대문자로 정정했다 (EX-10·LY-15 준용).
 */
public enum MeetingTransitionAction {
    OPEN, // 개회 — 예정 → 진행 (TR-M1)
    WRITE_MINUTES, // 회의록작성 — 진행 → 회의록작성 (TR-M2)
    CLOSE, // 종료 — 회의록작성 → 종료 (TR-M3). 미처리 안건이 남으면 차단
    CANCEL // 취소 — 예정 → 취소 (TR-M4). 사유가 필수다
}
