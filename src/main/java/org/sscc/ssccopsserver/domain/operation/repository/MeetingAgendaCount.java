package org.sscc.ssccopsserver.domain.operation.repository;

/*
 * 회의별 안건 건수 집계 프로젝션 (GET /v1/meetings 목록 카드의 "안건 N건").
 * 안건이 한 건도 없는 회의는 GROUP BY 결과에 아예 나오지 않는다.
 */
public interface MeetingAgendaCount {

    Long getMeetingId();

    long getAgendaCount();
}
