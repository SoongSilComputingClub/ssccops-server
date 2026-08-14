package org.sscc.ssccopsserver.domain.operation.repository;

/*
 * 하위 업무별 체크리스트 완료 집계 결과 (OPS-003).
 *
 * 상위 업무 상세는 하위 업무마다 진행률을 보여주는데, 하위 업무 수만큼 체크리스트를 조회하면
 * 그대로 N+1이 된다 (DB-13). 항목 전체가 아니라 개수만 있으면 되므로 한 번의 집계 쿼리로
 * 받아오기 위한 프로젝션이다.
 *
 * 체크리스트가 한 건도 없는 하위 업무는 GROUP BY 결과에 아예 나오지 않는다. 그 경우를
 * 0건으로 볼지는 여기가 아니라 호출부가 정한다.
 */
public interface SubWorkChecklistProgress {

    Long getSubWorkId();

    long getTotalCount();

    long getCompletedCount();
}
