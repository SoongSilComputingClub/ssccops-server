package org.sscc.ssccopsserver.domain.operation.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.operation.entity.WorkEntity;

public interface WorkRepository extends JpaRepository<WorkEntity, Long>, WorkRepositoryCustom {

    /*
     * 소프트 삭제 여부는 부모 oper가 관리하므로 조인해서 걸러낸다.
     * work 자체에는 del_dt가 없다.
     *
     * 상세 조회(OPS-003)가 담당자·등록자를 이름까지 내려야 하므로 연관을 한 번에 끌어온다 —
     * LAZY 그대로 두면 응답 조립 단계에서 연관마다 쿼리가 더 나간다 (DB-13).
     */
    @EntityGraph(attributePaths = {"operation", "operation.personInCharge", "operation.registrant"})
    Optional<WorkEntity> findByIdAndOperationDeletedAtIsNull(Long id);

    /*
     * 목록 조회(OPS-020)가 페이지 봉투에 싣는 '전체 건수'. 필터를 걸지 않았을 때의 건수라
     * 조건이 없고, 삭제된 건을 빼는 기준만 목록과 같아야 한다 (AGG-03).
     */
    long countByOperationDeletedAtIsNull();
}
