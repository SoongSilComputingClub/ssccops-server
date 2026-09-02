package org.sscc.ssccopsserver.domain.academicprogram.repository;

/*
 * 회차별 출석 집계 결과 (#135 회차 목록의 presentCount/totalCount).
 *
 * 목록은 회차마다 "N/M 참석"을 보여주는데, 회차 수만큼 집계 쿼리를 날리면 그대로 N+1이다
 * (DB-13 · EventParticipantCount 선례). 한 번의 집계 쿼리로 받아오기 위한 프로젝션이다.
 *
 * 출석 행이 하나도 없는 회차는 GROUP BY 결과에 나오지 않는다 — 0/0으로 채우는 것은 호출부다.
 */
public interface SessionAttendanceCount {

    Long getSessionId();

    long getTotalCount();

    long getPresentCount();
}
