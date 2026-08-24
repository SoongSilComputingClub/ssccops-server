package org.sscc.ssccopsserver.domain.academicprogram.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.academicprogram.entity.CurriculumItemEntity;

public interface CurriculumItemRepository extends JpaRepository<CurriculumItemEntity, Long> {

    long countByAcademicProgramId(Long academicProgramId);

    /*
     * 계획 조회(GET .../curriculum-items, #134). academicProgramId로 좁히는 것이 "다른 활동의
     * 커리큘럼 혼입 방지"이며, 서비스가 걸러 내는 것이 아니라 질의가 애초에 그 활동 것만 읽는다.
     *
     * 정렬은 회차 번호다 — 화면이 회차 이력 표라 등록 순서(PK)가 아니라 seqno가 곧 줄 순서다.
     */
    List<CurriculumItemEntity> findByAcademicProgramIdOrderBySeqnoAsc(Long academicProgramId);
}
