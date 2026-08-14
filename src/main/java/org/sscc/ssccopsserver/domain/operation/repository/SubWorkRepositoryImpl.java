package org.sscc.ssccopsserver.domain.operation.repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

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
     * 체크리스트는 컬렉션이라 여기서 fetch join 하지 않는다 (DB-14) — 페이징과 함께 쓰면
     * Hibernate가 전체를 메모리로 읽은 뒤 자른다. 진행률은 집계 쿼리로 따로 센다.
     */
    private static final String SELECT_ROWS =
            "select s from SubWorkEntity s"
                    + " join fetch s.operation o"
                    + " join fetch o.personInCharge"
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
         * 지연: 마감이 지났는데 아직 완료되지 않은 건. dly_yn 컬럼을 읽지 않는다 —
         * 등록 시 false로 고정된 뒤 갱신하는 주체가 없어 항상 false다.
         */
        if (query.overdueOnly()) {
            conditions.append(" and s.dueAt < :now and s.workStatus <> :doneStatus");
            parameters.put("now", query.now());
            parameters.put("doneStatus", WorkStatus.DONE);
        }
        /*
         * 마감 임박: 아직 마감 전이면서 지정한 시각 안에 마감되는 건. 이미 지난 건을 빼는 것은
         * 그러지 않으면 '마감임박' 칩이 '지연' 칩을 통째로 포함해 두 칩이 겹치기 때문이다.
         */
        if (query.hasDueBeforeFilter()) {
            conditions.append(
                    " and s.dueAt >= :now and s.dueAt <= :dueBefore"
                            + " and s.workStatus <> :doneStatus");
            parameters.put("now", query.now());
            parameters.put("dueBefore", query.dueBefore());
            parameters.put("doneStatus", WorkStatus.DONE);
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
