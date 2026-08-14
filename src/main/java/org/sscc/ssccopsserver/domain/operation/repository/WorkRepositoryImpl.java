package org.sscc.ssccopsserver.domain.operation.repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import org.sscc.ssccopsserver.domain.operation.dto.WorkCursor;
import org.sscc.ssccopsserver.domain.operation.dto.WorkSearchQuery;
import org.sscc.ssccopsserver.domain.operation.dto.WorkSortOrder;
import org.sscc.ssccopsserver.domain.operation.entity.WorkEntity;

import lombok.RequiredArgsConstructor;

/*
 * 상위 업무 목록 조회(OPS-020)의 동적 쿼리 구현. Spring Data가 이름 규칙으로 찾아
 * WorkRepository에 합쳐 주므로 별도 등록이 필요 없다.
 *
 * JPQL을 문자열로 조립하지만 조립에 들어가는 조각은 전부 이 클래스의 상수와 enum에서 나온다 —
 * 요청 값은 예외 없이 이름 있는 파라미터로만 바인딩한다.
 *
 * 필터는 work의 컬럼(work_stts_cd·work_type_cd)이고 정렬 키는 oper의 컬럼(crt_dt·bgng_dt)이라
 * 하나의 인덱스로 덮이지 않는다. 두 테이블로 나뉜 구조를 그대로 받아들이고 양쪽에 인덱스를
 * 두었다 (DB-17 · 엔티티의 @Table 참고).
 */
@RequiredArgsConstructor
public class WorkRepositoryImpl implements WorkRepositoryCustom {

    /*
     * 카드 한 장이 제목·기간·담당자 이름까지 쓰므로 연관을 한 번에 끌어온다 (DB-13).
     * 제목과 기간은 work가 아니라 그 oper에 있다.
     *
     * 하위 업무는 컬렉션이라 여기서 fetch join 하지 않는다 (DB-14) — 페이징과 함께 쓰면
     * Hibernate가 전체를 메모리로 읽은 뒤 자른다. 건수와 진행률은 집계 쿼리로 따로 센다.
     */
    private static final String SELECT_ROWS =
            "select w from WorkEntity w join fetch w.operation o join fetch o.personInCharge";

    private static final String SELECT_COUNT =
            "select count(w) from WorkEntity w join w.operation o";

    // 소프트 삭제 여부는 부모 oper가 관리한다. 삭제된 건은 목록에도 건수에도 없다 (AGG-03)
    private static final String BASE_CONDITION = " where o.deletedAt is null";

    private static final String CREATED_AT_PATH = "o.createdAt";
    private static final String START_AT_PATH = "o.beginAt";

    private final EntityManager entityManager;

    @Override
    public List<WorkEntity> search(WorkSearchQuery query) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        String jpql =
                SELECT_ROWS
                        + filterConditions(query, parameters)
                        + cursorCondition(query, parameters)
                        + orderBy(query.sort());

        TypedQuery<WorkEntity> typedQuery = entityManager.createQuery(jpql, WorkEntity.class);
        parameters.forEach(typedQuery::setParameter);
        // 다음 페이지 존재 여부를 알기 위해 한 건 더 읽는다
        return typedQuery.setMaxResults(query.fetchSize()).getResultList();
    }

    // 커서·정렬은 건수와 무관하므로 빼고 센다
    @Override
    public long countMatching(WorkSearchQuery query) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        String jpql = SELECT_COUNT + filterConditions(query, parameters);

        TypedQuery<Long> typedQuery = entityManager.createQuery(jpql, Long.class);
        parameters.forEach(typedQuery::setParameter);
        return typedQuery.getSingleResult();
    }

    /*
     * 카드 배지와 같은 축인 두 필터. 값이 없는 필터는 조건 자체를 붙이지 않는다 —
     * ':param is null or ...' 형태로 항상 붙이면 옵티마이저가 인덱스를 못 쓴다.
     */
    private String filterConditions(WorkSearchQuery query, Map<String, Object> parameters) {
        StringBuilder conditions = new StringBuilder(BASE_CONDITION);

        if (query.hasWorkStatusFilter()) {
            conditions.append(" and w.workStatus = :workStatus");
            parameters.put("workStatus", query.workStatus());
        }
        if (query.hasWorkTypeFilter()) {
            conditions.append(" and w.workType = :workType");
            parameters.put("workType", query.workType());
        }
        return conditions.toString();
    }

    /*
     * 커서보다 뒤에 있는 건들. 정렬 키가 같은 건이 여럿일 수 있어 식별자로 동률을 끊는다.
     *
     * 정렬 키가 NULL인 행(시작 일시 없는 업무)은 항상 뒤에 모아 두므로(nulls last),
     * 커서가 NULL 구간에 있으면 그 구간에서 식별자만 비교하고, 아직 앞 구간이면 NULL 구간
     * 전체가 뒤에 있다는 뜻이라 조건에 함께 넣는다.
     */
    private String cursorCondition(WorkSearchQuery query, Map<String, Object> parameters) {
        if (!query.hasCursor()) {
            return "";
        }
        WorkCursor cursor = query.cursor();
        String path = sortPath(query.sort());
        parameters.put("cursorId", cursor.workId());

        if (cursor.sortValue() == null) {
            return " and " + path + " is null and w.id > :cursorId";
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
                + " = :cursorKey and w.id > :cursorId))";
    }

    /*
     * 정렬은 AGG-06을 따른다 — 등록 최신순이 기본이고, 시작 일시로 정렬하면 시작 일시 없는
     * 건이 뒤로 간다. NULL 정렬 기본값이 H2(먼저)와 PostgreSQL(나중)에서 갈리므로 nulls last를
     * 명시해야 테스트와 운영이 같은 순서를 낸다.
     *
     * 동률 정렬은 방향과 무관하게 항상 식별자 오름차순이다. 커서 비교식도 같은 방향을 쓰므로
     * 둘을 함께 바꾸지 않는 한 여기만 뒤집으면 안 된다.
     */
    private String orderBy(WorkSortOrder sort) {
        String direction = sort.isDescending() ? "desc" : "asc";
        String nullsLast = sort.isNullableKey() ? " nulls last" : "";
        return " order by " + sortPath(sort) + " " + direction + nullsLast + ", w.id asc";
    }

    private String sortPath(WorkSortOrder sort) {
        return sort.getKey() == WorkSortOrder.SortKey.START_AT ? START_AT_PATH : CREATED_AT_PATH;
    }
}
