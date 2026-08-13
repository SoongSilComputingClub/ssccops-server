package org.sscc.ssccopsserver.domain.operation.entity;

/*
 * oper.oper_type_cd — 운영 건이 업무(work)인지 회의(mtg)인지 구분하는 기준 코드.
 * 어느 확장 테이블이 이 운영 건을 상속하는지를 가리킨다.
 */
public enum OperationType {
    WORK,
    MEETING
}
