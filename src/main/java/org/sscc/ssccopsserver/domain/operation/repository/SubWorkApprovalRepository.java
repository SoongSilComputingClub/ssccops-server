package org.sscc.ssccopsserver.domain.operation.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkApprovalEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;

public interface SubWorkApprovalRepository extends JpaRepository<SubWorkApprovalEntity, Long> {

    // 반려 후 재승인이 가능해 하위 업무당 여러 건이 쌓인다. 승인된 순서대로 준다
    List<SubWorkApprovalEntity> findBySubWorkOrderByApprovedAtAsc(SubWorkEntity subWork);
}
