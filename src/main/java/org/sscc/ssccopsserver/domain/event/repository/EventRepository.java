package org.sscc.ssccopsserver.domain.event.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;

public interface EventRepository extends JpaRepository<EventEntity, Long> {}
