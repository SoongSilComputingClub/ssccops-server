package org.sscc.ssccopsserver.domain.member.repository;

/*
 * 역할 분류별 소속 역할 수 집계 결과 (#80 역할 분류 목록).
 *
 * 역할 분류 관리 화면은 분류마다 "소속 역할 N건"을 보여주고 그 값으로 삭제 버튼을 잠근다.
 * 분류 수만큼 count 질의를 날리면 그대로 N+1이 되므로 GROUP BY 한 번으로 받아오기 위한
 * 프로젝션이다 (DB-13). FormLabelUsageCount·FormResponseCount와 같은 방식이다.
 *
 * 소속 역할이 하나도 없는 분류는 GROUP BY 결과에 아예 나오지 않는다. 그 경우를 0건으로 볼지는
 * 여기가 아니라 호출부가 정한다.
 */
public interface RoleClassificationRoleCount {

    String getRoleClsfCd();

    long getRoleCount();
}
