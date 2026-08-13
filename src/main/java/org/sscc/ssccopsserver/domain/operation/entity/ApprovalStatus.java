package org.sscc.ssccopsserver.domain.operation.entity;

/*
 * sub_work.aprv_stts_cd — 하위 업무 승인 상태 기준 코드. 데이터사전이 정의한 5종이다.
 * 데이터사전이 표시명(불필요·대기·승인·반려·재승인필요)만 적고 있어 저장 코드값은
 * 같은 테이블의 work_stts_cd 표기를 따라 영문 대문자로 둔다 (개발지침서 EX-10·LY-15).
 *
 * 업무 상태(WorkStatus)와는 별개 축이다 — API 정의서 OPS-010 응답이 둘을 각각 내려준다.
 * 등록 시점에는 유형의 승인 필요 여부(sub_work_type.aprv_need_yn)만으로 결정하고,
 * 승인·반려 전이는 OPS-014가 붙을 때 전이 메서드로 구현한다 (AR-10·LY-14).
 */
public enum ApprovalStatus {
    NOT_REQUIRED, // 불필요
    PENDING, // 대기
    APPROVED, // 승인
    REJECTED, // 반려
    REAPPROVAL_REQUIRED // 재승인필요
}
