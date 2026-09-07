package org.sscc.ssccopsserver.domain.operation.repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import org.sscc.ssccopsserver.domain.operation.dto.KeywordSearch;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkCursor;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkSearchQuery;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkSortOrder;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.WorkStatus;

import lombok.RequiredArgsConstructor;

/*
 * 목록 조회(OPS-008)의 동적 쿼리 구현. Spring Data가 이름 규칙으로 찾아 SubWorkRepository에
 * 합쳐 주므로 별도 등록이 필요 없다.
 *
 * JPQL을 문자열로 조립하지만 조립에 들어가는 조각은 전부 이 클래스의 상수와 enum에서 나온다 —
 * 요청 값은 예외 없이 이름 있는 파라미터로만 바인딩한다.
 */
@RequiredArgsConstructor
public class SubWorkRepositoryImpl implements SubWorkRepositoryCustom {

    /*
     * 목록 한 행이 상위 업무 제목·유형명·담당자 이름까지 쓰므로 연관을 한 번에 끌어온다 (DB-13).
     * 상위 업무의 제목은 work가 아니라 그 oper에 있어 work.operation까지 따라간다.
     *
     * 등록자(o.registrant)까지 끌어오는 것은 같은 조회를 쓰는 승인함 카드(OPS-017)가 요청자
     * 이름을 그리기 때문이다. 담당자와 등록자가 같은 건에서는 티가 나지 않지만 다른 건이 섞이면
     * 카드 수만큼 회원 조회가 따라붙는다. **left join**인 것은 등록자가 NULL일 수 있어서다
     * (이관 데이터 대비로 nullable) — inner join이면 그 행이 목록에서 통째로 사라진다.
     *
     * 체크리스트는 컬렉션이라 여기서 fetch join 하지 않는다 (DB-14) — 페이징과 함께 쓰면
     * Hibernate가 전체를 메모리로 읽은 뒤 자른다. 진행률은 집계 쿼리로 따로 센다.
     */
    private static final String SELECT_ROWS =
            "select s from SubWorkEntity s"
                    + " join fetch s.operation o"
                    + " join fetch o.personInCharge"
                    + " left join fetch o.registrant"
                    + " join fetch s.subWorkType"
                    + " join fetch s.work w"
                    + " join fetch w.operation";

    private static final String SELECT_COUNT =
            "select count(s) from SubWorkEntity s join s.operation o";

    // 소프트 삭제 여부는 부모 oper가 관리한다. 삭제된 건은 목록에도 건수에도 없다 (AGG-03)
    private static final String BASE_CONDITION = " where o.deletedAt is null";

    private static final String DUE_AT_PATH = "s.dueAt";
    private static final String CREATED_AT_PATH = "o.createdAt";

    private final EntityManager entityManager;

    @Override
    public List<SubWorkEntity> search(SubWorkSearchQuery query) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        String jpql =
                SELECT_ROWS
                        + filterConditions(query, parameters)
                        + cursorCondition(query, parameters)
                        + orderBy(query.sort());

        TypedQuery<SubWorkEntity> typedQuery = entityManager.createQuery(jpql, SubWorkEntity.class);
        parameters.forEach(typedQuery::setParameter);
        // 다음 페이지 존재 여부를 알기 위해 한 건 더 읽는다
        return typedQuery.setMaxResults(query.fetchSize()).getResultList();
    }

    /*
     * 커서·정렬은 건수와 무관하므로 빼고 센다. 화면 우상단의 '8건'이 이 값이다.
     */
    @Override
    public long countMatching(SubWorkSearchQuery query) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        String jpql = SELECT_COUNT + filterConditions(query, parameters);

        TypedQuery<Long> typedQuery = entityManager.createQuery(jpql, Long.class);
        parameters.forEach(typedQuery::setParameter);
        return typedQuery.getSingleResult();
    }

    /*
     * 화면 필터 칩이 만드는 조건들. 값이 없는 필터는 조건 자체를 붙이지 않는다 —
     * ':param is null or ...' 형태로 항상 붙이면 옵티마이저가 인덱스를 못 쓴다.
     */
    private String filterConditions(SubWorkSearchQuery query, Map<String, Object> parameters) {
        StringBuilder conditions = new StringBuilder(BASE_CONDITION);

        if (query.hasWorkStatusFilter()) {
            conditions.append(" and s.workStatus = :workStatus");
            parameters.put("workStatus", query.workStatus());
        }
        if (query.hasApprovalStatusFilter()) {
            conditions.append(" and s.approvalStatus in :approvalStatuses");
            parameters.put("approvalStatuses", query.approvalStatuses());
        }
        /*
         * 지연: 마감일이 지났는데 아직 완료되지 않은 건. dly_yn 컬럼을 읽지 않는다 —
         * 채우지 않기로 결정한 컬럼이라 항상 false다 (SubWorkEntity.delayed 주석).
         * 조건은 SubWorkEntity.isDelayedBefore와 같아야 한다 — 단건과 목록이 갈리면 안 된다.
         *
         * 경계는 '지금'이 아니라 오늘 0시다 (DeadlinePolicy, #121). 마감일이 오늘인 건은
         * 시각이 지났어도 아직 지연이 아니다.
         */
        if (query.overdueOnly()) {
            conditions.append(" and s.dueAt < :overdueBefore and s.workStatus <> :doneStatus");
            parameters.put("overdueBefore", query.overdueBefore());
            parameters.put("doneStatus", WorkStatus.DONE);
        }
        /*
         * 마감 임박: 아직 마감일이 지나지 않았으면서 지정한 시각 안에 마감되는 건. 이미 지난
         * 건을 빼는 것은 그러지 않으면 '마감임박' 칩이 '지연' 칩을 통째로 포함해 두 칩이
         * 겹치기 때문이다.
         *
         * 하한이 지연 조건의 경계와 **같은 값**이어야 한다. 한쪽만 오늘 0시로 옮기면 '오늘
         * 09시 마감'인 건이 정오에 지연 칩에서도 빠지고 마감임박 칩에도 잡히지 않아 어느
         * 칩에서도 보이지 않게 된다 (#121).
         */
        if (query.hasDueBeforeFilter()) {
            conditions.append(
                    " and s.dueAt >= :overdueBefore and s.dueAt <= :dueBefore"
                            + " and s.workStatus <> :doneStatus");
            parameters.put("overdueBefore", query.overdueBefore());
            parameters.put("dueBefore", query.dueBefore());
            parameters.put("doneStatus", WorkStatus.DONE);
        }
        /*
         * 정체 ① — 완료 점검을 다 채웠는데 검토요청 전인 건 (ssccops#196). 조건은
         * SubWorkEntity.isReadyForReview와 같아야 한다. 항목이 하나라도 있어야 하고(없으면
         * '전부 체크'가 공허하게 참이다) 미완료 항목이 없어야 한다. 체크리스트는 페이징과
         * 함께 fetch join 하지 못하므로(DB-14) 상관 서브쿼리로 묻는다.
         */
        if (query.readyForReviewOnly()) {
            conditions.append(
                    " and s.workStatus in :preReviewStatuses"
                            + " and exists (select 1 from SubWorkChecklistItemEntity i"
                            + " where i.subWork = s)"
                            + " and not exists (select 1 from SubWorkChecklistItemEntity u"
                            + " where u.subWork = s and u.completed = false)");
            parameters.put(
                    "preReviewStatuses", List.of(WorkStatus.PLANNING, WorkStatus.IN_PROGRESS));
        }
        /*
         * 정체 ② — 검토요청이 경계 시각보다 앞인데 아직 검토 상태인 건 (ssccops#196). 조건은
         * SubWorkEntity.isReviewStaleBefore와 같아야 한다. 요청 시각은 이력의 마지막 검토
         * 진입(sub_work_stts_hstry.aftr_work_stts_cd = REVIEW)이다 — 승인함 카드의 '요청 …'과
         * 같은 값이라(SubWorkStatusHistoryRepository.findReviewRequestsBySubWorkIds) 두
         * 화면이 다른 시각을 말하지 않는다. 경계는 오늘 0시 기준의 일자 판정이다
         * (DeadlinePolicy.reviewStaleBefore).
         */
        if (query.reviewStaleOnly()) {
            conditions.append(
                    " and s.workStatus = :reviewStatus"
                            + " and (select max(h.changedAt) from SubWorkStatusHistoryEntity h"
                            + " where h.subWork = s and h.nextWorkStatus = :reviewStatus)"
                            + " < :reviewStaleBefore");
            parameters.put("reviewStatus", WorkStatus.REVIEW);
            parameters.put("reviewStaleBefore", query.reviewStaleBefore());
        }
        /*
         * 제목 부분 일치 (ssccops#216). 여기서 찾는 것은 **하위 업무 자신의 제목**이다 —
         * o는 s.operation이고 상위 업무의 oper는 별칭이 다르다. 상위 제목까지 함께 훑으면
         * 회의 안건 추가에서 상위 업무 이름만 아는 사람이 하위 업무를 찾을 수 있어 편해 보이지만,
         * 검색어와 눈에 보이는 제목이 어긋난 행이 결과에 섞여 "왜 이게 나왔나"가 설명되지 않는다.
         *
         * 업무 쪽과 같은 규칙을 쓴다(KeywordSearch) — 두 목록이 안건 추가 화면에 나란히 놓이므로
         * 한쪽만 대소문자를 가리면 종류를 바꾼 순간 같은 검색어가 다른 결과를 낸다.
         * o는 목록·건수 두 쿼리 모두에서 join된 alias라 countMatching에도 자동으로 걸린다.
         */
        if (query.hasKeywordFilter()) {
            conditions.append(
                    " and lower(o.title) like lower(:keyword) escape '"
                            + KeywordSearch.ESCAPE
                            + "'");
            parameters.put("keyword", KeywordSearch.toLikePattern(query.keyword()));
        }
        /*
         * 담당자가 나인 건만 (ssccops#225). 상위 업무 쪽과 같은 조건이며 담당자는 sub_work가
         * 아니라 그 oper에 있다(pic_id). o가 이미 join된 alias라 countMatching에도 함께 걸린다.
         *
         * o.personInCharge.id는 FK 컬럼을 그대로 읽는다 — 통째로 비교하면 회원 테이블에
         * join이 하나 더 붙는데 필요한 것은 식별자뿐이다.
         *
         * 승인 대기 건은 이 축에 들어오지 않는다. '내가 승인해야 할 것'은 담당이 아니라 권한
         * 판정(ApprovalAuthorityPolicy)이고 승인함이 이미 그 화면이다.
         */
        if (query.hasPersonInChargeFilter()) {
            conditions.append(" and o.personInCharge.id = :personInChargeId");
            parameters.put("personInChargeId", query.personInChargeId());
        }
        return conditions.toString();
    }

    /*
     * 커서보다 뒤에 있는 건들. 정렬 키가 같은 건이 여럿일 수 있어 식별자로 동률을 끊는다.
     *
     * 정렬 키가 NULL인 행(마감 없는 하위 업무)은 항상 뒤에 모아 두므로(nulls last),
     * 커서가 NULL 구간에 있으면 그 구간에서 식별자만 비교하고, 아직 앞 구간이면 NULL 구간
     * 전체가 뒤에 있다는 뜻이라 조건에 함께 넣는다.
     */
    private String cursorCondition(SubWorkSearchQuery query, Map<String, Object> parameters) {
        if (!query.hasCursor()) {
            return "";
        }
        SubWorkCursor cursor = query.cursor();
        String path = sortPath(query.sort());
        parameters.put("cursorId", cursor.subWorkId());

        if (cursor.sortValue() == null) {
            return " and " + path + " is null and s.id > :cursorId";
        }

        parameters.put("cursorKey", cursor.sortValue());
        String comparison = query.sort().isDescending() ? "<" : ">";
        String nullTail = query.sort().isNullableKey() ? path + " is null or " : "";
        return " and ("
                + nullTail
                + path
                + " "
                + comparison
                + " :cursorKey or ("
                + path
                + " = :cursorKey and s.id > :cursorId))";
    }

    /*
     * 정렬은 AGG-04를 따른다 — 마감 오름차순, 마감 없는 건은 뒤, 동률이면 식별자 오름차순.
     * NULL 정렬 기본값이 H2(먼저)와 PostgreSQL(나중)에서 갈리므로 nulls last를 명시해야
     * 테스트와 운영이 같은 순서를 낸다.
     *
     * 동률 정렬은 방향과 무관하게 항상 식별자 오름차순이다. 커서 비교식도 같은 방향을 쓰므로
     * 둘을 함께 바꾸지 않는 한 여기만 뒤집으면 안 된다.
     */
    private String orderBy(SubWorkSortOrder sort) {
        String direction = sort.isDescending() ? "desc" : "asc";
        String nullsLast = sort.isNullableKey() ? " nulls last" : "";
        return " order by " + sortPath(sort) + " " + direction + nullsLast + ", s.id asc";
    }

    private String sortPath(SubWorkSortOrder sort) {
        return sort.getKey() == SubWorkSortOrder.SortKey.DUE_AT ? DUE_AT_PATH : CREATED_AT_PATH;
    }
}
