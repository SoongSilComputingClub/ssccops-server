package org.sscc.ssccopsserver.domain.operation.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.WorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.WorkStatus;

public interface SubWorkRepository extends JpaRepository<SubWorkEntity, Long> {

    /*
     * 상세 조회(OPS-009)용 단건 조회. 소프트 삭제 여부는 부모 oper가 관리하므로 조인해서
     * 걸러내고, 삭제된 건은 없는 것으로 본다(404).
     *
     * 상세 응답이 담당자·등록자 이름과 유형명·상위 업무 식별자를 모두 쓰므로 연관을 한 번에
     * 끌어온다 — LAZY 그대로 두면 응답 조립 단계에서 연관마다 쿼리가 더 나간다 (DB-13).
     */
    @EntityGraph(
            attributePaths = {
                "operation",
                "operation.personInCharge",
                "operation.registrant",
                "subWorkType",
                "work"
            })
    Optional<SubWorkEntity> findByIdAndOperationDeletedAtIsNull(Long id);

    /*
     * 상위 업무 진행률 집계용. 소프트 삭제 여부는 부모 oper가 관리하므로(sub_work에는
     * del_dt가 없다) 조인해서 걸러낸다 — 삭제된 하위 업무는 분모에 들어가면 안 된다.
     */
    long countByWorkAndOperationDeletedAtIsNull(WorkEntity work);

    long countByWorkAndWorkStatusAndOperationDeletedAtIsNull(
            WorkEntity work, WorkStatus workStatus);
}
