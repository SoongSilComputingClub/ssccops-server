package org.sscc.ssccopsserver.domain.operation.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.operation.entity.OperationEntity;

public interface OperationRepository extends JpaRepository<OperationEntity, Long> {

    // 소프트 삭제되지 않은 운영 건만 조회한다 (del_dt IS NULL)
    Optional<OperationEntity> findByIdAndDeletedAtIsNull(Long id);
}
