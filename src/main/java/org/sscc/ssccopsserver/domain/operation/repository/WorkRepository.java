package org.sscc.ssccopsserver.domain.operation.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.operation.entity.WorkEntity;

public interface WorkRepository extends JpaRepository<WorkEntity, Long> {

    /*
     * 소프트 삭제 여부는 부모 oper가 관리하므로 조인해서 걸러낸다.
     * work 자체에는 del_dt가 없다.
     */
    Optional<WorkEntity> findByIdAndOperationDeletedAtIsNull(Long id);
}
