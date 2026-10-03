package org.sscc.ssccopsserver.domain.operation.repository;

/*
 * 태그별 사용 업무 수 집계 결과 (#624 태그 관리 목록). 태그마다 count를 부르면 그대로 N+1이 되므로
 * GROUP BY 한 번으로 받아오는 프로젝션이다 (DB-13 · FormLabelUsageCount와 같다).
 *
 * 한 번도 쓰이지 않은 태그는 결과에 나오지 않는다 — 0건으로 보는 것은 호출부가 정한다.
 */
public interface WorkTagUsageCount {

    Long getTagId();

    long getUsageCount();
}
