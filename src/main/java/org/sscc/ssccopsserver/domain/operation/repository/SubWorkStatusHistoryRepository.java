package org.sscc.ssccopsserver.domain.operation.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkStatusHistoryEntity;
import org.sscc.ssccopsserver.domain.operation.entity.WorkStatus;

public interface SubWorkStatusHistoryRepository
        extends JpaRepository<SubWorkStatusHistoryEntity, Long> {

    /*
     * 한 하위 업무의 전이 이력을 일어난 순서대로. OPS-011(상태 전환 이력 조회)이 쓸 정렬이며
     * 지금은 전이가 이력을 제대로 쌓는지 검증하는 데 쓴다. 이력은 조회 전용이라
     * 수정·삭제 메서드를 열지 않는다 (POL-004·AP-09).
     */
    List<SubWorkStatusHistoryEntity> findBySubWorkOrderByChangedAtAsc(SubWorkEntity subWork);

    /*
     * 승인 회차 판정용 (#47). 검토(REVIEW)에 들어간 횟수가 곧 지금이 몇 번째 승인 절차인지다 —
     * 반려되면 진행으로 돌아갔다가 다시 검토로 올라오기 때문이다.
     *
     * 회차를 sub_work의 컬럼으로 두지 않고 이력에서 파생하는 것은, 갱신 주체가 하나 더 늘면
     * 이력과 컬럼이 어긋날 수 있어서다. 이력은 불변이라 다시 세도 같은 값이 나온다.
     */
    long countBySubWorkAndNextWorkStatus(SubWorkEntity subWork, WorkStatus nextWorkStatus);

    /*
     * 승인함(OPS-017)이 카드마다 쓰는 '몇 번째 회차인가'와 '언제 올라왔는가'를 한 번에 집계한다.
     * 카드마다 이력을 조회하면 그대로 N+1이다 (DB-13).
     *
     * 빈 컬렉션을 넘기면 IN () 이 되어 DB에 따라 문법 오류가 나므로 호출 전에 걸러야 한다.
     */
    @Query(
            "select s.id as subWorkId,"
                    + " count(h) as reviewCount,"
                    + " max(h.changedAt) as lastRequestedAt"
                    + " from SubWorkStatusHistoryEntity h"
                    + " join h.subWork s"
                    + " where s.id in :subWorkIds and h.nextWorkStatus = :nextWorkStatus"
                    + " group by s.id")
    List<SubWorkReviewRequest> findReviewRequestsBySubWorkIds(
            @Param("subWorkIds") Collection<Long> subWorkIds,
            @Param("nextWorkStatus") WorkStatus nextWorkStatus);
}
