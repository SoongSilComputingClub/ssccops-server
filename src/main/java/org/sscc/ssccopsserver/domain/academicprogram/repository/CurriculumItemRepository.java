package org.sscc.ssccopsserver.domain.academicprogram.repository;

import java.util.List;
import java.util.Optional;

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

    /*
     * 회차 기록 제출(#135)이 가리키는 계획 항목. 식별자만으로 찾으면 다른 활동의 커리큘럼에
     * 실적을 매달 수 있다 — 활동을 함께 조건에 넣어 애초에 남의 것이 조회되지 않게 한다
     * (폼 응답의 findByIdAndForm, 참가자의 findByIdAndEvent와 같은 자리).
     */
    Optional<CurriculumItemEntity> findByIdAndAcademicProgramId(Long id, Long academicProgramId);
}
