package org.sscc.ssccopsserver.domain.academicprogram.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;

public interface AcademicProgramRepository
        extends JpaRepository<AcademicProgramEntity, Long>, AcademicProgramRepositoryCustom {

    /*
     * 단건 조회(#131)가 event·type·제출자·리더 이름까지 한 번에 내려야 하므로 연관을 한
     * 번에 끌어온다 — LAZY 그대로 두면 응답 조립 단계에서 연관마다 쿼리가 더 나간다(DB-13).
     */
    @Override
    @EntityGraph(attributePaths = {"event", "type", "proposer", "leader"})
    Optional<AcademicProgramEntity> findById(Long id);

    /*
     * 이미 이관된 기획안인가 (#150). UNIQUE(uk_acdm_actv_form_rspns)가 있는데도 선조회를
     * 두는 것은 위반을 사유 있는 409로 돌려주기 위해서다 — 제약만 두면 원인 모를 500이 되고,
     * 선조회만 두면 동시 요청이 그대로 통과한다(#20 회원가입이 세운 규칙 그대로).
     */
    boolean existsByFormResponse(FormResponseHistoryEntity formResponse);

    /*
     * 행사 도메인이 "이 event가 학술 프로그램인가, 어느 유형인가"를 묻는 자리다 (#187 · #519 ·
     * ADR-0043). event ↔ acdm_actv은 1:1(uk_acdm_actv_event)이라 event_id로 찾으면 행이 최대
     * 하나이고, 호출부(공개·운영 목록)가 이미 읽어 온 event 집합에 대해서만 물으므로 IN 하나로
     * 끝난다 — event 도메인이 이 판별을 직접 구현하지 않고 학술 도메인에 물어보는 유일한
     * 진입점이다(AcademicEventLinkProvider).
     *
     * 처음(#187)에는 event id 집합만 돌려줬는데, 행사 응답이 유형까지 싣게 되면서 프로그램
     * 식별자·유형 코드·유형 이름을 함께 뽑는다 — 존재 여부만 묻던 자리(공개 노출 판정·삭제
     * 가드)도 같은 질의를 쓴다. 같은 사실에 질의를 두 벌 두지 않기 위해서다.
     */
    @Query(
            "select ap.event.id as eventId, ap.id as academicProgramId,"
                    + " ap.type.code as typeCd, ap.type.name as typeNm"
                    + " from AcademicProgramEntity ap where ap.event.id in :eventIds")
    List<AcademicProgramEventLink> findEventLinksByEventIdIn(
            @Param("eventIds") Collection<Long> eventIds);

    /*
     * 폼 상세(#190)와 폼 수정 방어선이 "이 폼이 학술 활동에 연결됐는가"를 묻는 자리다. form ↔
     * event는 event.form_id(uk_event_form), event ↔ acdm_actv은 acdm_actv.event_id(1:1)라
     * form → event → acdm_actv을 한 번에 거슬러 오르면 나온다. 학술 이관 폼의 event 분류는
     * 그냥 "EVENT"라 분류 코드로는 일반 폼과 구별되지 않으므로(#187) 이 조인이 유일한 판별이며,
     * form 도메인이 학술 도메인에 묻는 진입점이다(findEventIdsByEventIdIn과 같은 갈래).
     */
    @Query("select ap.id from AcademicProgramEntity ap where ap.event.form.id = :formId")
    Optional<Long> findIdByFormId(@Param("formId") Long formId);
}
