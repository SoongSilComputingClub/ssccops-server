package org.sscc.ssccopsserver.domain.event.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.event.entity.EventParticipantEntity;

public interface EventParticipantRepository extends JpaRepository<EventParticipantEntity, Long> {}
