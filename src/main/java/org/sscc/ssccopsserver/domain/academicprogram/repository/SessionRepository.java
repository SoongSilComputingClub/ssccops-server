package org.sscc.ssccopsserver.domain.academicprogram.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.sscc.ssccopsserver.domain.academicprogram.entity.SessionEntity;

public interface SessionRepository
        extends JpaRepository<SessionEntity, Long>, SessionRepositoryCustom {

    /** 중복 제출 선조회(#135). uk_session_curriculum_item 위반이 최종 방어선이다 */
    boolean existsByCurriculumItemId(Long curriculumItemId);

    /*
     * 활동 범위 검사(#135). 회차 식별자만으로 찾으면 /v1/academic-programs/1/sessions/999가
     * 다른 활동의 진행 내용과 출석부를 그대로 돌려준다 — 폼 응답의 findByIdAndForm,
     * 참가자의 findByIdAndEvent와 같은 자리이며, 없는 회차와 남의 활동 회차는 같은 404다.
     *
     * 상세 응답이 계획(회차 번호·주제·예정일)과 작성자 이름까지 한 번에 내리므로 연관을 함께
     * 끌어온다 — LAZY 그대로 두면 응답 조립 단계에서 조회가 더 나간다(DB-13).
     */
    @EntityGraph(attributePaths = {"curriculumItem", "registrant"})
    @Query(
            "select s from SessionEntity s"
                    + " where s.id = :sessionId"
                    + " and s.curriculumItem.academicProgram.id = :academicProgramId")
    Optional<SessionEntity> findByIdAndAcademicProgramId(
            @Param("sessionId") Long sessionId, @Param("academicProgramId") Long academicProgramId);

    /*
     * 계획 조회(#134 · GET .../curriculum-items)가 붙이는 실적. 커리큘럼 항목마다 "실적이
     * 있나"를 물으면 그대로 N+1이라(DB-13) 활동 하나의 실적을 한 번에 읽어 호출부가 계획에
     * 접는다. 계획이 이미 활동으로 좁혀 읽히므로 여기서도 활동으로 좁힌다.
     */
    List<SessionEntity> findByCurriculumItemAcademicProgramId(Long academicProgramId);
}
