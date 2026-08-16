package org.sscc.ssccopsserver.domain.operation.entity;

/*
 * work.work_type_cd — 업무 유형 기준 코드.
 * 데이터사전이 정의한 3종이 전부다. API 정의서 OPS-002에는 8종으로 적혀 있으나
 * 데이터사전이 스키마의 기준 원천이므로 3종을 따른다.
 */
public enum WorkType {
    EVENT,
    REGULAR,
    ROUTINE
}
