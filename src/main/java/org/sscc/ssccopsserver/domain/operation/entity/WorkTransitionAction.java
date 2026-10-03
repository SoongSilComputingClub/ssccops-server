package org.sscc.ssccopsserver.domain.operation.entity;

/*
 * 상위 업무 상태 전이 액션 (#622 · ssccops#563). 상태(WorkStatus)는 V1부터 넷이었지만 옮길 길이
 * 없어 모든 업무가 기획(PLANNING)에 머물렀다 — 이 enum이 그 길이다.
 *
 * 하위 업무 전이(TransitionAction)와 상태 어휘는 같지만 enum을 나눴다. 하위 업무의 승인·완료는
 * 결재 권한·정족수·완료 점검을 달고 반려에는 사유가 필수인데, 상위 업무의 완료는 «하위 업무가
 * 전부 완료인가» 하나만 보고 되돌리기(검토 되돌리기·재개)에 사유를 받지 않는다. 한 enum에 섞으면
 * 하위 업무 전이 요청에 REOPEN이, 상위 업무에 APPROVE_COMPLETE가 «허용값»으로 광고된다.
 *
 * 전이표에 없는 조합(기획 → 완료 등)은 이 enum이 아니라 WorkEntity.applyTransition이 진입 상태를
 * 검증해 막는다 (AR-10·LY-14). 기준 코드에 없는 값은 역직렬화 단계에서 INVALID_CODE_VALUE(400)다.
 */
public enum WorkTransitionAction {
    START, // 착수 — 기획 → 진행
    REQUEST_REVIEW, // 검토 요청 — 진행 → 검토
    COMPLETE, // 완료 — 검토 → 완료. 완료가 아닌 하위 업무가 하나라도 남으면 막는다
    REVERT_REVIEW, // 검토 되돌리기 — 검토 → 진행
    REOPEN // 재개 — 완료 → 진행. 잘못 누른 완료가 DB 작업이 되지 않게 남긴 길이다
}
