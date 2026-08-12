package org.sscc.ssccopsserver.domain.example.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.example.entity.ExampleEntity;
import org.sscc.ssccopsserver.domain.example.entity.ExampleStatus;

public interface ExampleRepository extends JpaRepository<ExampleEntity, Long> {

    Optional<ExampleEntity> findByIdAndStatusNot(Long id, ExampleStatus status);
}
