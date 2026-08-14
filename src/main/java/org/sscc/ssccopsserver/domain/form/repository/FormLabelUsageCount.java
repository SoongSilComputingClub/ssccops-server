package org.sscc.ssccopsserver.domain.form.repository;

/*
 * 라벨별 사용 폼 수 집계 결과 (#34 라벨 관리 목록).
 *
 * 라벨 관리 화면은 라벨마다 "사용 중인 폼 N건"을 보여준다. 라벨 수만큼 카운트 쿼리를 날리면
 * 그대로 N+1이 되므로 GROUP BY 한 번으로 받아오기 위한 프로젝션이다 (DB-13).
 * SubWorkChecklistProgress·FormResponseCount와 같은 방식이다.
 *
 * 한 번도 쓰이지 않은 라벨은 GROUP BY 결과에 아예 나오지 않는다. 그 경우를 0건으로 볼지는
 * 여기가 아니라 호출부가 정한다.
 *
 * 집계 대상에는 비활성 라벨의 연결도 그대로 들어간다 — 비활성은 새로 달 수 없다는 뜻일 뿐이라
 * 이미 달린 폼은 여전히 그 라벨을 쓰고 있고, 화면도 그 건수를 보고 비활성화 여부를 판단한다.
 */
public interface FormLabelUsageCount {

    Long getLabelId();

    long getUsageCount();
}
