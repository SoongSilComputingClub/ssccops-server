package org.sscc.ssccopsserver.domain.operation.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.WorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.WorkStatus;

public interface SubWorkRepository extends JpaRepository<SubWorkEntity, Long> {

    /*
     * 상위 업무 진행률 집계용. 소프트 삭제 여부는 부모 oper가 관리하므로(sub_work에는
     * del_dt가 없다) 조인해서 걸러낸다 — 삭제된 하위 업무는 분모에 들어가면 안 된다.
     */
    long countByWorkAndOperationDeletedAtIsNull(WorkEntity work);

    long countByWorkAndWorkStatusAndOperationDeletedAtIsNull(
            WorkEntity work, WorkStatus workStatus);
}
