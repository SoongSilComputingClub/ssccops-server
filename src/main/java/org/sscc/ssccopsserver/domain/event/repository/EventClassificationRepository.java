package org.sscc.ssccopsserver.domain.event.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.event.entity.EventClassificationEntity;

public interface EventClassificationRepository
        extends JpaRepository<EventClassificationEntity, String> {

    List<EventClassificationEntity> findAllByOrderByDisplayOrderAsc();
}
