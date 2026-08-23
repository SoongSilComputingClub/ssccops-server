package org.sscc.ssccopsserver.domain.academicprogram.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;

public interface AcademicProgramRepository
        extends JpaRepository<AcademicProgramEntity, Long>, AcademicProgramRepositoryCustom {

    /*
     * 단건 조회(#131)가 event·type·제출자·리더 이름까지 한 번에 내려야 하므로 연관을 한
     * 번에 끌어온다 — LAZY 그대로 두면 응답 조립 단계에서 연관마다 쿼리가 더 나간다(DB-13).
     */
    @Override
    @EntityGraph(attributePaths = {"event", "type", "proposer", "leader"})
    Optional<AcademicProgramEntity> findById(Long id);
}
