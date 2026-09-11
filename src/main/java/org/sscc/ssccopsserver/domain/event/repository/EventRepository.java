package org.sscc.ssccopsserver.domain.event.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.sscc.ssccopsserver.domain.event.code.EventStatus;
import org.sscc.ssccopsserver.domain.event.entity.EventClassificationEntity;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;

/*
 * 행사 조회.
 *
 * ── 지워진 행사 (#347 · ADR-0020) ──
 *
 * **모든 조회가 del_dt를 본다.** 파생 메서드는 이름에 DeletedAtIsNull을 갖고 JPQL은 where에
 * `e.deletedAt is null`을 갖는다. ADR-0014가 소프트 삭제를 기각한 이유가 "조회 하나만 필터를
 * 빠뜨려도 지운 행사가 익명 공개 목록에 뜬다"였고, 그 위험을 감수하는 방법이 폼(#329)과 같은
 * 이 규칙이다 — 조건을 서비스의 `if (event.isDeleted())`가 아니라 질의에 넣는 것은, 분기 하나가
 * 빠지는 것으로 지운 행사가 그 화면에서만 계속 보이기 때문이다.
 *
 * 예외는 셋이고 각각 이름이 그것을 말한다 — 휴지통 목록(DeletedAtIsNotNull), 삭제·복구 경로가
 * 쓰는 findById(필터 없음 · 서비스의 findEventIncludingDeleted 하나만 부른다), 그리고 분류
 * 사용 집계(IncludingDeleted · 아래 주석).
 */
public interface EventRepository extends JpaRepository<EventEntity, Long> {

    /*
     * 살아 있는 행사 단건 (#347). 운영 상세·수정·전이·복제·참가자 명단·공유 링크 발급이 전부
     * 이것을 지난다 — 지워진 행사는 없는 행사와 같은 404다.
     *
     * **삭제·복구 경로는 이것을 쓰지 않는다.** 그쪽은 지워진 행사를 찾아내야 "없는 행사"와
     * "이미 지운 행사"를 409로 갈라 줄 수 있어 findById(필터 없음)를 쓴다 (FormRepository 선례).
     */
    Optional<EventEntity> findByIdAndDeletedAtIsNull(Long id);

    /*
     * 살아 있는 행사 존재 여부 (#347). 이미지 업로드 URL 발급(EventImageServiceImpl)이 "이 행사에
     * 키를 내줘도 되는가"만 묻는 자리다 — 지워진 행사에는 올릴 수 없다(ADR-0020 규칙 · 404).
     * 엔티티를 읽어 오지 않는 것은 그 경로가 행사의 어떤 값도 쓰지 않기 때문이다.
     */
    boolean existsByIdAndDeletedAtIsNull(Long id);

    /*
     * 휴지통 목록 (#347 · GET /v1/events/deleted). 지운 시각 역순이라 방금 지운 것이 맨 위다 —
     * 되살리기를 누르는 사람이 찾는 것은 대개 직전에 지운 행사다.
     *
     * 운영 목록(findAllForList)과 달리 분류·상태 필터를 받지 않는다. 휴지통은 거를 만큼 쌓이는
     * 화면이 아니고, 필터를 붙이면 목록 질의의 조건이 두 벌이 된다 (FormRepository의 휴지통과
     * 같은 판단).
     *
     * 목록 항목이 분류명과 연결 폼(receiptStatus 파생)을 쓰므로 함께 끌어온다 (DB-13). 폼은
     * 없을 수 있어 EntityGraph의 left join이 맞다.
     */
    @EntityGraph(attributePaths = {"classification", "form"})
    List<EventEntity> findAllByDeletedAtIsNotNullOrderByDeletedAtDescIdDesc();

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
     * **지워진 행사는 어느 쪽에도 없다** (#347 · `e.deletedAt is null`). 상태 집합과 나란히 두지
     * 않고 언제나 붙는 조건으로 둔 것은 두 축이 다른 종류이기 때문이다 — 게시 상태는 운영진이
     * 고르는 값이지만 삭제 여부는 고를 수 있는 값이 아니다. 상태에 DELETED를 더하면 "전체"에
     * 지운 행사가 섞여 들어오고, 무엇보다 공개 목록이 넘기는 집합이 하나만 빠져도 익명에게
     * 나간다. 휴지통은 별도 조회(위)가 답한다.
     *
     * 분류(eventClsfNm)와 연결 폼(receiptStatus 파생)이 목록에 필요하므로 함께 페치한다 —
     * LAZY 그대로 두면 목록 한 줄마다 조회가 두 번씩 더 나간다 (DB-13). 폼은 없을 수 있어
     * left join fetch다.
     */
    @Query(
            "select e from EventEntity e join fetch e.classification left join fetch e.form"
                    + " where e.deletedAt is null"
                    + " and e.status in :statuses"
                    + " and (:classificationCode is null"
                    + "   or e.classification.code = :classificationCode)"
                    + " order by e.id desc")
    List<EventEntity> findAllForList(
            @Param("statuses") Collection<EventStatus> statuses,
            @Param("classificationCode") String classificationCode);

    /*
     * 공개 상세 조회 (ssccops#143). 식별자와 상태를 **함께** 조건에 넣는다 — 식별자로 찾은 뒤
     * 상태를 보고 거르면 그 분기 하나가 빠지는 것으로 작성 중인 행사의 본문이 익명에게 나간다
     * (폼 응답의 findByIdAndForm 범위 검사와 같은 자리). 지워진 행사(#347)도 같은 이유로 같은
     * 질의 안에서 걸러 없는 행사와 같은 404가 된다.
     */
    Optional<EventEntity> findByIdAndDeletedAtIsNullAndStatus(Long id, EventStatus status);

    /*
     * 공유 미리보기가 여는 범위 (ssccops#312). 식별자와 상태를 **함께** 조건에 넣는 이유는
     * 바로 위와 같다 — 상태 분기를 호출부에 두면 그 한 줄이 빠지는 것으로 열지 않기로 한 것이
     * 익명에게 나간다.
     *
     * 넘기는 집합은 DRAFT·PUBLISHED다. **보관(ARCHIVED)을 빼는 것이 이 질의의 요점이다** —
     * 공개 상세가 보관된 행사를 404로 답하는데(EventStatus 주석) 카드만 계속 열리면 익명에게
     * 답하는 두 층이 서로 다른 말을 한다. 게시된 행사를 넣는 것은 반대 방향의 같은 이유다:
     * 게시 전에 나눈 링크가 **게시되는 순간 죽으면** 볼 수 있게 된 시점에 카드가 깨진다.
     *
     * 지워진 행사(#347)는 공유 링크 착지에서도 404다 — 치운 행사의 제목이 링크로 계속 열리면
     * "지웠다"는 화면의 표시가 사실이 아니게 된다(삭제된 운영 건을 없는 것으로 답하는
     * SubWorkSharePreviewProvider와 같은 자리).
     */
    Optional<EventEntity> findByIdAndDeletedAtIsNullAndStatusIn(
            Long id, Collection<EventStatus> statuses);

    /*
     * 내 신청 목록이 쓰는 "이 폼들이 붙은 행사" (ssccops#145). 폼은 살아 있는 행사에 전속이므로
     * (uk_event_form) 폼 하나가 행사 하나로 풀린다.
     *
     * **행사 상태를 조건에 넣지 않는다.** 공개 목록(#156)이 PUBLISHED만 내보내는 것과 갈리는
     * 지점인데, 그쪽이 지키는 것은 "아직 공개하지 않은 것을 남에게 보이지 않는다"이고 여기 대상은
     * **본인이 실제로 낸 신청**이다. 운영자가 행사를 보관(ARCHIVED)하거나 작성 중으로 되돌린다고
     * 해서 내가 낸 신청이 사라지면 그 목록이 사실과 어긋난다 — 신청 이력은 행사의 게시 상태와
     * 독립이다.
     *
     * **지워진 행사는 뺀다** (#347). 보관과 달리 삭제는 그 신청 항목이 '내 신청'에서 빠지는 것을
     * 대가로 받아들인 결정이고(ADR-0020 · 폼 #329와 같은 판단), 무엇보다 지운 행사가 붙잡던 폼을
     * 다른 행사가 가져갈 수 있어 필터가 없으면 폼 하나가 행사 둘로 풀려 호출부의 Map이 깨진다.
     * 이 필터는 FormResponseHistoryRepository.findEventApplicationsByMember의 exists 조건과
     * 짝이다 — 한쪽만 걸면 응답은 오는데 행사가 없어 NPE가 된다.
     *
     * 분류명(eventClsfNm)이 응답에 실리므로 분류를 함께 페치한다 — 없으면 신청 한 줄마다 조회가
     * 더 나간다 (DB-13).
     */
    @Query(
            "select e from EventEntity e join fetch e.classification join fetch e.form f"
                    + " where e.deletedAt is null and f.id in :formIds")
    List<EventEntity> findAllByFormIdIn(@Param("formIds") Collection<Long> formIds);

    /*
     * 폼 전속(D11) 선조회. **지운 행사는 폼을 붙잡지 않는다** (#347) — 잘못 만든 행사를 지웠는데
     * 그 신청 폼을 다른 행사가 못 쓰면 폼까지 새로 만들어야 하고, 그것은 삭제를 연 이유를
     * 절반만 채운다.
     *
     * PostgreSQL에서는 부분 유니크 인덱스 uk_event_form(WHERE del_dt IS NULL · V8)이 최종
     * 방어선이지만, H2(테스트)는 부분 인덱스를 지원하지 않아 **이 선조회가 H2에서의 규칙 전부다.**
     * 선조회가 있어야 PostgreSQL에서도 대부분의 요청이 500이 아니라 바로 409 FORM_ALREADY_LINKED를
     * 받는다.
     */
    boolean existsByFormAndDeletedAtIsNull(FormEntity form);

    /*
     * 수정·복구 경로의 전속 선조회 — 자기 자신이 이미 연결한 폼은 전속 위반이 아니다. 복구
     * (EventServiceImpl.restoreEvent)는 지워진 동안 그 폼을 가져간 살아 있는 행사가 있는지를
     * 이것으로 묻는다.
     */
    boolean existsByFormAndIdNotAndDeletedAtIsNull(FormEntity form, Long id);

    /*
     * 분류 삭제 가드(D13) — 사용 중이면 409 EVENT_CLASSIFICATION_IN_USE.
     *
     * **지워진 행사도 센다 — DeletedAtIsNull 규칙의 의도된 예외다** (#347). event.event_clsf_cd는
     * NOT NULL FK라 휴지통의 행사도 분류를 물리적으로 붙잡고 있다. 지운 행사를 빼고 "사용 중
     * 아님"으로 답하면 분류 DELETE가 FK 위반으로 떨어지고, DataIntegrityViolationException은
     * 전역 핸들러가 없어 **500**이 된다 — ADR-0014가 하드 삭제에서 발견한 것과 정확히 같은 경로다.
     * 그래서 이 판정의 기준은 "화면에 보이는가"가 아니라 "행이 참조하는가"이고, 운영진은 휴지통을
     * 비우는 길이 없으므로 그 행사를 되살려 분류를 옮긴 뒤에야 분류를 지울 수 있다.
     *
     * 파생 이름이 아니라 JPQL인 것은 이름에 IncludingDeleted를 넣기 위해서다 — 파생 규칙으로는
     * 그 접미사가 속성으로 읽혀 기동이 실패하고, 이름 없이 두면 이 파일의 규칙("모든 조회가
     * del_dt를 본다")을 어기는 자리가 겉으로 드러나지 않는다.
     */
    @Query("select count(e) > 0 from EventEntity e where e.classification = :classification")
    boolean existsByClassificationIncludingDeleted(
            @Param("classification") EventClassificationEntity classification);

    /*
     * 분류 수정 응답이 "사용 중 N건"을 다시 실어 줄 때 쓴다. 지워진 행사를 세는 이유는 바로 위와
     * 같다 — 가드가 보는 수와 화면이 보는 수가 다르면 "0건인데 지울 수 없다"가 된다.
     */
    @Query("select count(e) from EventEntity e where e.classification = :classification")
    long countByClassificationIncludingDeleted(
            @Param("classification") EventClassificationEntity classification);

    /*
     * 분류별 사용 행사 수 일괄 집계 (ssccops#140 분류 목록). 분류마다 세면 그대로 N+1이다
     * (DB-13, MemberRoleRepository.countRolesByClassification 선례).
     *
     * 행사가 하나도 없는 분류는 GROUP BY 결과에 나오지 않는다 — 0으로 채우는 것은 호출부다.
     * 지워진 행사를 세는 이유는 existsByClassificationIncludingDeleted와 같다.
     */
    @Query(
            "select c.code as eventClsfCd, count(e) as eventCount"
                    + " from EventEntity e join e.classification c"
                    + " group by c.code")
    List<EventClassificationUsageCount> countEventsGroupedByClassificationIncludingDeleted();
}
