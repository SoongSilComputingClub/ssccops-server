package org.sscc.ssccopsserver.domain.event.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.sscc.ssccopsserver.domain.event.code.EventStatus;
import org.sscc.ssccopsserver.domain.event.entity.EventClassificationEntity;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;

public interface EventRepository extends JpaRepository<EventEntity, Long> {

    /*
     * 행사 목록 (ssccops#139 · GET /v1/events, ssccops#143 · GET /public/v1/events). 분류·상태
     * 두 필터가 각각 선택이고 둘 다 주면 AND다.
     *
     * 상태는 집합으로 받아 "전체"를 전체 상태로 표현한다 — 열거형 파라미터에 NULL을 넣고
     * :status is null로 분기하면 Hibernate가 타입을 추론하지 못한다 (FormRepository 선례).
     * 분류 코드는 String이라 NULL 비교가 안전해 그대로 선택 필터로 둔다.
     *
     * **어떤 상태를 보여줄지는 부르는 쪽이 정한다.** 운영자 목록은 요청의 필터(미지정이면 전체)를
     * 넘기고 공개 목록은 PUBLISHED 하나로 고정해 넘긴다 — 질의를 두 벌로 나누면 페치 조인과
     * 정렬이 두 곳에서 각각 관리되고, 한쪽만 고쳐진 순서 때문에 같은 행사가 화면마다 다른
     * 자리에 놓인다.
     *
     * 분류(eventClsfNm)와 연결 폼(receiptStatus 파생)이 목록에 필요하므로 함께 페치한다 —
     * LAZY 그대로 두면 목록 한 줄마다 조회가 두 번씩 더 나간다 (DB-13). 폼은 없을 수 있어
     * left join fetch다.
     */
    @Query(
            "select e from EventEntity e join fetch e.classification left join fetch e.form"
                    + " where e.status in :statuses"
                    + " and (:classificationCode is null"
                    + "   or e.classification.code = :classificationCode)"
                    + " order by e.id desc")
    List<EventEntity> findAllForList(
            @Param("statuses") Collection<EventStatus> statuses,
            @Param("classificationCode") String classificationCode);

    /*
     * 공개 상세 조회 (ssccops#143). 식별자와 상태를 **함께** 조건에 넣는다 — 식별자로 찾은 뒤
     * 상태를 보고 거르면 그 분기 하나가 빠지는 것으로 작성 중인 행사의 본문이 익명에게 나간다
     * (폼 응답의 findByIdAndForm 범위 검사와 같은 자리).
     */
    Optional<EventEntity> findByIdAndStatus(Long id, EventStatus status);

    /*
     * 내 신청 목록이 쓰는 "이 폼들이 붙은 행사" (ssccops#145). 폼은 행사에 전속이므로
     * (uk_event_form) 폼 하나가 행사 하나로 풀린다.
     *
     * **행사 상태를 조건에 넣지 않는다.** 공개 목록(#156)이 PUBLISHED만 내보내는 것과 갈리는
     * 지점인데, 그쪽이 지키는 것은 "아직 공개하지 않은 것을 남에게 보이지 않는다"이고 여기 대상은
     * **본인이 실제로 낸 신청**이다. 운영자가 행사를 보관(ARCHIVED)하거나 작성 중으로 되돌린다고
     * 해서 내가 낸 신청이 사라지면 그 목록이 사실과 어긋난다 — 신청 이력은 행사의 게시 상태와
     * 독립이다.
     *
     * 분류명(eventClsfNm)이 응답에 실리므로 분류를 함께 페치한다 — 없으면 신청 한 줄마다 조회가
     * 더 나간다 (DB-13).
     */
    @Query(
            "select e from EventEntity e join fetch e.classification join fetch e.form f"
                    + " where f.id in :formIds")
    List<EventEntity> findAllByFormIdIn(@Param("formIds") Collection<Long> formIds);

    /*
     * 폼 전속(D11) 선조회. UNIQUE(uk_event_form)가 최종 방어선이지만 선조회가 있어야
     * 대부분의 요청이 500이 아니라 바로 409 FORM_ALREADY_LINKED를 받는다.
     */
    boolean existsByForm(FormEntity form);

    /** 수정 경로의 전속 선조회 — 자기 자신이 이미 연결한 폼은 전속 위반이 아니다 */
    boolean existsByFormAndIdNot(FormEntity form, Long id);

    /** 분류 삭제 가드(D13) — 사용 중이면 409 EVENT_CLASSIFICATION_IN_USE */
    boolean existsByClassification(EventClassificationEntity classification);

    /** 분류 수정 응답이 "사용 중 N건"을 다시 실어 줄 때 쓴다 */
    long countByClassification(EventClassificationEntity classification);

    /*
     * 분류별 사용 행사 수 일괄 집계 (ssccops#140 분류 목록). 분류마다 세면 그대로 N+1이다
     * (DB-13, MemberRoleRepository.countRolesByClassification 선례).
     *
     * 행사가 하나도 없는 분류는 GROUP BY 결과에 나오지 않는다 — 0으로 채우는 것은 호출부다.
     */
    @Query(
            "select c.code as eventClsfCd, count(e) as eventCount"
                    + " from EventEntity e join e.classification c"
                    + " group by c.code")
    List<EventClassificationUsageCount> countEventsGroupedByClassification();
}
