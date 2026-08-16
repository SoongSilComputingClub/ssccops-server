package org.sscc.ssccopsserver.domain.operation.entity;

/*
 * oper.oper_type_cd — 운영 건이 업무(work)·하위 업무(sub_work)·회의(mtg) 중 무엇인지
 * 구분하는 기준 코드. 어느 확장 테이블이 이 운영 건을 상속하는지를 가리킨다.
 *
 * 데이터사전의 oper_type_cd 설명에는 WORK·MEETING만 적혀 있으나, 하위 업무도 제목·기간·
 * 담당자를 oper에 두는 확장 테이블이므로 SUB_WORK가 필요하다. API 정의서 OPS-001의
 * 운영 건 유형 필터도 WORK|SUB_WORK|MEETING 3종을 요구한다.
 */
public enum OperationType {
    WORK,
    SUB_WORK,
    MEETING
}
