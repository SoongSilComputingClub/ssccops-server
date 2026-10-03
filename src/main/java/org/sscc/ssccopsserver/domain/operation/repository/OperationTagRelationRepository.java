package org.sscc.ssccopsserver.domain.operation.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.sscc.ssccopsserver.domain.operation.entity.OperationEntity;
import org.sscc.ssccopsserver.domain.operation.entity.OperationTagEntity;
import org.sscc.ssccopsserver.domain.operation.entity.OperationTagRelationEntity;

/*
 * 운영 건-태그 연결 (#637). OperationEntity가 태그 컬렉션을 들고 있지 않으므로(목록 N+1을 피한다 —
 * FormEntity와 같은 결정) 지정 교체와 관리 목록은 이 리포지토리를 거친다.
 *
 * 업무·하위 업무·회의 목록·상세에 싣는 태그는 여기가 아니라 OperationRepository.findTagRelationsByOperationIds가
 * 읽는다 — 그 주석 참고.
 */
public interface OperationTagRelationRepository
        extends JpaRepository<OperationTagRelationEntity, Long> {

    /** 지정 교체가 지금 걸린 것과 요청을 비교할 때 쓴다. 응답에 이름이 필요해 태그를 함께 끌어온다 */
    @EntityGraph(attributePaths = "tag")
    List<OperationTagRelationEntity> findAllByOperation(OperationEntity operation);

    /*
     * 태그 삭제의 관계 정리. DB에도 ON DELETE CASCADE가 있지만(V31) 영속성 컨텍스트는 그것을
     * 모른다 — 같은 트랜잭션에서 관계를 먼저 지워 두어야 이후 조회가 지운 행을 들고 있지 않는다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from OperationTagRelationEntity r where r.tag = :tag")
    int deleteAllByTag(@Param("tag") OperationTagEntity tag);

    /*
     * 태그별 사용 운영 건 수(관리 목록) — 업무·하위 업무·회의를 합친 수다. **지운 운영 건은 세지
     * 않는다** — 삭제는 소프트 삭제(oper.del_dt)라 관계 행이 남는데, 그것까지 세면 목록·필터에서는
     * 보이지 않는 건이 건수에만 잡힌다. 폼 라벨(FormLabelRelationRepository)은 폼 삭제 이전에
     * 만들어져 이 조건이 없다.
     *
     * (oper_id, oper_tag_id) UNIQUE라 행 수가 곧 운영 건 수다 — DISTINCT가 필요 없다.
     */
    @Query(
            """
            select r.tag.id as tagId, count(r) as usageCount
            from OperationTagRelationEntity r
            where r.tag.id in :tagIds and r.operation.deletedAt is null
            group by r.tag.id
            """)
    List<OperationTagUsageCount> findUsageCountsByTagIds(@Param("tagIds") Collection<Long> tagIds);
}
