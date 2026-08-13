package org.sscc.ssccopsserver.domain.operation.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkChecklistItemEntity;

public interface SubWorkChecklistItemRepository
        extends JpaRepository<SubWorkChecklistItemEntity, Long> {}
