package org.sscc.ssccopsserver.domain.operation.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkChecklistItemEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;

public interface SubWorkChecklistItemRepository
        extends JpaRepository<SubWorkChecklistItemEntity, Long> {

    /*
     * 상세 조회(OPS-009)의 완료 체크리스트. 화면 표시 순서가 유형에 적힌 항목 순서이므로
     * 정렬을 쿼리에 고정한다 — 클라이언트가 다시 정렬하지 않아도 되게 한다.
     */
    List<SubWorkChecklistItemEntity> findBySubWorkOrderBySortOrderAsc(SubWorkEntity subWork);

    /*
     * 완료 승인 전이(TR-03)의 선행 조건 판정용. 남은 항목이 0건이어야 완료할 수 있다.
     * 항목 전체를 로딩해 세지 않는다 — 판정에 필요한 것은 개수뿐이다.
     * 유형에 완료 점검 항목이 없어 체크리스트가 비어 있으면 0건이라 그대로 통과한다.
     */
    long countBySubWorkAndCompletedFalse(SubWorkEntity subWork);

    /*
     * 상위 업무 상세(OPS-003)의 하위 업무별 진행률 산출용. 하위 업무마다 조회하면 N+1이 되므로
     * 목록 전체를 한 번에 집계한다 (DB-13).
     *
     * 체크리스트가 없는 하위 업무는 결과에 나오지 않으므로 호출부가 0건으로 채워야 한다.
     * 빈 컬렉션을 넘기면 IN () 이 되어 DB에 따라 문법 오류가 나므로 호출 전에 걸러야 한다.
     */
    @Query(
            "select s.id as subWorkId,"
                    + " count(i) as totalCount,"
                    + " sum(case when i.completed = true then 1L else 0L end) as completedCount"
                    + " from SubWorkChecklistItemEntity i"
                    + " join i.subWork s"
                    + " where s.id in :subWorkIds"
                    + " group by s.id")
    List<SubWorkChecklistProgress> findProgressBySubWorkIds(
            @Param("subWorkIds") Collection<Long> subWorkIds);
}
