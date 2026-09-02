package org.sscc.ssccopsserver.domain.event.repository;

/*
 * 행사별 확정 참가자 수 집계 결과 (ssccops#139 행사 목록·상세의 confirmedCount).
 *
 * 목록은 행사마다 "확정 N명"을 보여주는데, 행사 수만큼 카운트 쿼리를 날리면 그대로 N+1이 된다
 * (DB-13). 한 번의 집계 쿼리로 받아오기 위한 프로젝션이다 (FormResponseCount 선례).
 *
 * 참가자가 한 명도 없는 행사는 GROUP BY 결과에 아예 나오지 않는다 — 0명으로 채우는 것은
 * 호출부다.
 */
public interface EventParticipantCount {

    Long getEventId();

    long getConfirmedCount();
}
