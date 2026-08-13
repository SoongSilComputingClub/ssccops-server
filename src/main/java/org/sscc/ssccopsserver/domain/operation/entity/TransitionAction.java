package org.sscc.ssccopsserver.domain.operation.entity;

/*
 * 하위 업무 상태 전이 액션 (OPS-010). API 정의서 02_상태_전이의 TR-01~TR-04를 그대로 옮겼다.
 *
 * 정의서는 액션명을 한글(착수·검토요청·승인·완료·반려)로 적고 있으나 저장·전송 값은
 * 영문 대문자로 둔다 (개발지침서 EX-10·LY-15). 기준 코드에 없는 값은 enum 역직렬화
 * 단계에서 걸러져 INVALID_CODE_VALUE(400)가 된다 (VL-09).
 *
 * 전이표에 없는 조합(완료 → 진행 되돌리기 등)은 이 enum이 아니라 SubWorkEntity의
 * 전이 메서드가 진입 상태를 검증해 차단한다 (AR-10·LY-14).
 */
public enum TransitionAction {
    START, // 착수 — 기획 → 진행 (TR-01)
    REQUEST_REVIEW, // 검토요청 — 진행 → 검토 (TR-02)
    APPROVE_COMPLETE, // 승인·완료 — 검토 → 완료 (TR-03). 승인과 완료가 한 단계다
    REJECT // 반려 — 검토 → 진행 (TR-04). 사유가 필수다
}
