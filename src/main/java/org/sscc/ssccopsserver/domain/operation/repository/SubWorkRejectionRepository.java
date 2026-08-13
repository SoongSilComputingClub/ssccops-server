package org.sscc.ssccopsserver.domain.operation.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkRejectionEntity;

public interface SubWorkRejectionRepository extends JpaRepository<SubWorkRejectionEntity, Long> {

    // 반려 → 보완 → 재검토요청 → 재반려가 가능해 하위 업무당 여러 건이 쌓인다
    List<SubWorkRejectionEntity> findBySubWorkOrderByRejectedAtAsc(SubWorkEntity subWork);
}
