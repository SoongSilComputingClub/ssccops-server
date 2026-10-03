package org.sscc.ssccopsserver.domain.operation.entity;

/*
 * work.work_stts_cd — 업무 상태 기준 코드. 데이터사전이 정의한 4종이다.
 * 데이터사전은 표시명(기획·진행·검토·완료)만 적고 있어 저장 코드값은 같은 테이블의
 * work_type_cd 표기를 따라 영문 대문자로 둔다 (개발지침서 EX-10·LY-15).
 *
 * REQ-019는 보류·취소를 포함한 6종을 요구하나 데이터사전에 없어 반영하지 않았다.
 * 상위 업무의 전이는 WorkEntity.applyTransition(#622 · WorkTransitionAction)이, 하위 업무의
 * 전이는 SubWorkEntity.applyTransition(TransitionAction)이 맡는다 (AR-10·LY-14).
 */
public enum WorkStatus {
    PLANNING, // 기획
    IN_PROGRESS, // 진행
    REVIEW, // 검토
    DONE // 완료
}
