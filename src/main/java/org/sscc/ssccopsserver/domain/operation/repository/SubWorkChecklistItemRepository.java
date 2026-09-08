package org.sscc.ssccopsserver.domain.operation.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

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
     * 체크·해제(OPS-013)의 대상 항목. itemId만으로 찾으면 경로의 subWorkId와 무관한 남의
     * 항목을 체크할 수 있으므로(IDOR) 소속을 조건에 함께 건다. 소속이 다른 항목은 조회되지
     * 않아 호출부가 404로 처리하며, 403으로 나누지 않는다 — 존재 사실을 알려주지 않는다.
     */
    Optional<SubWorkChecklistItemEntity> findByIdAndSubWork(Long id, SubWorkEntity subWork);

    /*
     * 항목 추가(#307)가 새 sort_seq를 매길 자리. 끝에 붙이므로 지금 가장 큰 값 + 1이다.
     * 항목이 하나도 없거나(체크리스트 없는 유형) 전부 지워졌으면 NULL이라 호출부가 1로 시작한다.
     *
     * count가 아니라 max인 것은 삭제가 하드이기 때문이다 — 3개 중 2번을 지우면 count는 2라
     * 다음 항목이 3번이 아니라 3번과 겹친다. 지운 뒤 남은 항목의 번호를 다시 매기지 않는
     * 결정(SubWorkChecklistItemEntity 주석)이 이 선택과 짝이다.
     */
    @Query("select max(i.sortOrder) from SubWorkChecklistItemEntity i where i.subWork = :subWork")
    Integer findMaxSortOrder(@Param("subWork") SubWorkEntity subWork);

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
