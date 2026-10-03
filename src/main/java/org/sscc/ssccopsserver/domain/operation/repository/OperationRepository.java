package org.sscc.ssccopsserver.domain.operation.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.sscc.ssccopsserver.domain.operation.entity.OperationEntity;
import org.sscc.ssccopsserver.domain.operation.entity.OperationTagRelationEntity;

public interface OperationRepository extends JpaRepository<OperationEntity, Long> {

    // 소프트 삭제되지 않은 운영 건만 조회한다 (del_dt IS NULL)
    Optional<OperationEntity> findByIdAndDeletedAtIsNull(Long id);

    /*
     * 업무·하위 업무·회의 목록·상세에 싣는 태그 칩 (#637). 이번 목록의 운영 건 전부를 한 번에 받아
     * 호출부가 운영 건별로 나눈다(OperationTagSummaryResponse.groupByOperationId) — 행마다 부르면
     * N+1이다 (DB-13). 정렬은 칩 순서(이름 오름차순)다.
     *
     * OperationTagRelationRepository가 아니라 여기 있는 것은 세 서비스(Work·SubWork·Meeting)가 이미
     * OperationRepository를 받고 있어서다 — 그 구현체들을 테스트 여러 곳이 new로 직접 만들므로
     * 생성자를 넓히면 그 호출이 전부 바뀐다.
     */
    @Query(
            "select r from OperationTagRelationEntity r join fetch r.tag t"
                    + " where r.operation.id in :operationIds order by t.name asc, t.id asc")
    List<OperationTagRelationEntity> findTagRelationsByOperationIds(
            @Param("operationIds") Collection<Long> operationIds);

    /*
     * 그 태그가 달린 운영 건 id (#637). 페이징이 없는 전량 목록(운영 통합 · 회의 목록)이 행을 거를 때
     * 쓴다 — 그 목록들은 어차피 전부 읽으므로 쿼리마다 exists를 더하는 대신 한 번 받아 메모리에서
     * 거른다. 페이징 목록(업무·하위 업무)은 커서·건수가 필터를 따라야 해 쿼리 안의 exists다.
     */
    @Query("select r.operation.id from OperationTagRelationEntity r where r.tag.id = :tagId")
    List<Long> findOperationIdsByTagId(@Param("tagId") Long tagId);
}
