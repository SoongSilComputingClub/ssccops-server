package org.sscc.ssccopsserver.domain.academicprogram.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.academicprogram.entity.CurriculumItemEntity;

/*
 * 계획 조회(GET .../curriculum-items)는 이 이슈(#131) 범위 밖이다(#134) — 지금 필요한 것은
 * 상세 응답의 curriculumItemCount뿐이다.
 */
public interface CurriculumItemRepository extends JpaRepository<CurriculumItemEntity, Long> {

    long countByAcademicProgramId(Long academicProgramId);
}
