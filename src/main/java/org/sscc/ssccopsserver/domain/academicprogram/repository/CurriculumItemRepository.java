package org.sscc.ssccopsserver.domain.academicprogram.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.sscc.ssccopsserver.domain.academicprogram.entity.CurriculumItemEntity;

public interface CurriculumItemRepository extends JpaRepository<CurriculumItemEntity, Long> {

    /*
     * 진행률 재료(#609) — 활동별 계획 항목 수와 그중 승인된 회차 수. 목록은 페이지의 활동 id를,
     * 상세는 id 하나를 넘긴다. 활동이 몇 건이든 질의는 하나다(DB-13).
     *
     * 계획에서 출발해 실적을 left join 한다 — 분모가 계획이라 실적이 없는 항목도 세어야 하고,
     * 분자는 그 계획에 매달린 실적만 보므로 승인 수가 항목 수를 넘을 수 없다
     * (sesn.crclm_artcl_id UNIQUE라 항목 하나에 실적이 최대 하나다 — count(c)도 그래서 부풀지 않는다).
     *
     * **APPROVED를 파라미터로 받지 않는다** — «무엇을 진척으로 세는가»가 이 질의에 박혀 있어야
     * 부르는 쪽이 제출됨(SUBMITTED)을 섞어 넘길 수 없다(RagDocumentRepository.findSearchable과
     * 같은 판단). 제출됨·수정요청은 학술국장이 아직 확정하지 않은 기록이라 세지 않는다.
     *
     * 계획 항목이 하나도 없는 활동은 결과에 나오지 않는다 — 0으로 채우는 것은 호출부다.
     */
    @Query(
            "select c.academicProgram.id as academicProgramId,"
                    + " count(c) as curriculumItemCount,"
                    + " sum(case when s.status ="
                    + " org.sscc.ssccopsserver.domain.academicprogram.entity.SessionStatus.APPROVED"
                    + " then 1L else 0L end) as approvedSessionCount"
                    + " from CurriculumItemEntity c"
                    + " left join SessionEntity s on s.curriculumItem = c"
                    + " where c.academicProgram.id in :academicProgramIds"
                    + " group by c.academicProgram.id")
    List<AcademicProgramProgressCount> countProgressByAcademicProgramIds(
            @Param("academicProgramIds") Collection<Long> academicProgramIds);

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
