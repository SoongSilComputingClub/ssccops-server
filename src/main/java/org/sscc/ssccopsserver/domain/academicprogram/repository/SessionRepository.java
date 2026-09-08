package org.sscc.ssccopsserver.domain.academicprogram.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.sscc.ssccopsserver.domain.academicprogram.entity.SessionEntity;

public interface SessionRepository
        extends JpaRepository<SessionEntity, Long>, SessionRepositoryCustom {

    /** 중복 제출 선조회(#135). uk_sesn_crclm_artcl 위반이 최종 방어선이다 */
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
     * 공유 미리보기(ssccops#311)가 회차 하나를 읽는 자리. **활동으로 좁히지 않는 유일한
     * 단건 조회다** — 토큰이 이미 회차 하나를 못 박고 있어(shr_lnk.trgt_id) 경로처럼 남의
     * 활동으로 흘러갈 자리가 없고, 활동 ID를 함께 요구하면 공유 도메인이 그 값을 어딘가에
     * 또 들고 있어야 한다.
     *
     * 제목이 계획(crclm_artcl.ttl)에 있으므로 함께 끌어온다 — LAZY 그대로면 미리보기를
     * 조립하며 조회가 한 번 더 나간다(DB-13).
     */
    @EntityGraph(attributePaths = {"curriculumItem"})
    Optional<SessionEntity> findWithCurriculumItemById(Long sessionId);

    /*
     * 계획 조회(#134 · GET .../curriculum-items)가 붙이는 실적. 커리큘럼 항목마다 "실적이
     * 있나"를 물으면 그대로 N+1이라(DB-13) 활동 하나의 실적을 한 번에 읽어 호출부가 계획에
     * 접는다. 계획이 이미 활동으로 좁혀 읽히므로 여기서도 활동으로 좁힌다.
     */
    List<SessionEntity> findByCurriculumItemAcademicProgramId(Long academicProgramId);

    /*
     * 인증사진 업로드(#137)가 회차 행을 잠근다. 잠그는 대상이 file_rfrnc가 아니라 sesn인
     * 것은, 막아야 하는 경합이 "이미 있는 참조를 둘이 고치는 것"이 아니라 **"참조가 아직 없는
     * 회차에 둘이 동시에 만드는 것"**이기 때문이다 — 없는 행은 잠글 수 없고, 그대로 두면 늦은
     * 쪽이 uk_file_rfrnc_sesn에 걸려 도메인 오류 코드 없는 500이 나간다.
     *
     * 재업로드를 UPSERT로 열어 둔 이상(설계 결정 #1) 그 경합의 정답은 거절이 아니라 순서를
     * 세우는 것이다 — 뒤에 들어온 요청은 앞의 커밋을 보고 그 참조를 갈아 끼운다. 이 회차의
     * 사진을 올릴 수 있는 사람은 스터디장 한 명뿐이라(소유권 정책) 잠금 경합 자체가 드물다.
     *
     * 반환값을 쓰지 않는 호출부가 있어도 질의는 필요하다 — 잠금은 SELECT ... FOR UPDATE가
     * 실제로 나가야 걸린다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SessionEntity s where s.id = :sessionId")
    Optional<SessionEntity> lockById(@Param("sessionId") Long sessionId);
}
