package org.sscc.ssccopsserver.domain.operation.repository;

import org.sscc.ssccopsserver.domain.operation.entity.WorkStatus;

/*
 * 상위 업무 목록(OPS-020)의 진행률·하위 업무 건수 집계용 프로젝션.
 *
 * 카드가 요구하는 값은 3단으로 겹쳐 있다 — 업무의 진행률(AGG-01)은 하위 업무 진행률의
 * 평균이고, 그 하위 업무 진행률(AGG-02)은 체크리스트 완료율에서 나온다. 업무마다 하위 업무를
 * 조회하고 하위 업무마다 체크리스트를 세면 N+1이 두 겹으로 쌓인다 (DB-13).
 *
 * 그래서 이번 페이지의 업무들에 달린 하위 업무를 한 번에 읽되, 엔티티가 아니라 집계에 필요한
 * 세 값만 가져온다. 업무 상태를 함께 싣는 것은 AGG-02가 '완료면 항목과 무관하게 100'이라
 * 체크리스트 개수만으로는 진행률을 정할 수 없기 때문이다.
 *
 * 소프트 삭제된 하위 업무는 쿼리에서 이미 빠진다 (AGG-03) — 목록에 보이는 건과 진행률의
 * 분모가 어긋나면 안 된다.
 */
public interface WorkSubWorkAggregate {

    Long getWorkId();

    Long getSubWorkId();

    WorkStatus getWorkStatus();
}
