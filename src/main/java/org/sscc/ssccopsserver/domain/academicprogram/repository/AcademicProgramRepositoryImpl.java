package org.sscc.ssccopsserver.domain.academicprogram.repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramCursor;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramMineRole;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramSearchQuery;
import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramSortOrder;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramEntity;

import lombok.RequiredArgsConstructor;

/*
 * 목록 조회(#131)의 동적 쿼리 구현. work 도메인의 WorkRepositoryImpl 골격을 따르되, 소프트
 * 삭제 컬럼이 없어 늘 붙는 기본 조건이 없다는 점은 member 도메인의 MemberRepositoryImpl과 같다
 * — 그래서 조건을 문자열 이어붙이기가 아니라 리스트로 모았다가 있을 때만 where를 붙인다
 * ('where 1=1' 같은 항상 참인 술어를 남기지 않기 위해서다).
 *
 * Spring Data가 <Repository이름>Impl을 같은 패키지에서 찾아 AcademicProgramRepository에
 * 합쳐 준다. 조립에 들어가는 조각은 전부 이 클래스의 상수·enum에서 나온다 — 요청 값은 예외
 * 없이 이름 있는 파라미터로만 바인딩한다.
 */
@RequiredArgsConstructor
public class AcademicProgramRepositoryImpl implements AcademicProgramRepositoryCustom {

    // like 와일드카드 이스케이프 문자. MemberRepositoryImpl과 같은 이유로 '!'를 쓴다
    private static final String LIKE_ESCAPE = "!";

    /*
     * SELECT_ROWS·SELECT_COUNT 양쪽에 같은 별칭(e·t·l)의 조인을 둔다 — SELECT_ROWS는
     * 화면이 필요로 하는 값을 한 번에 끌어오는 fetch join, SELECT_COUNT는 필터 조건이 같은
     * 별칭을 참조할 수 있게 하는 일반 join이다(WorkRepositoryImpl과 같은 이유).
     */
    private static final String SELECT_ROWS =
            "select a from AcademicProgramEntity a"
                    + " join fetch a.event e"
                    + " join fetch a.type t"
                    + " left join fetch a.leader l";

    private static final String SELECT_COUNT =
            "select count(a) from AcademicProgramEntity a"
                    + " join a.event e"
                    + " join a.type t"
                    + " left join a.leader l";

    /*
     * mine 필터의 두 역할(#215). 이 둘을 or로 묶은 것이 mine=true이며, 그 표기 하나에 두 역할이
     * 들어 있는 것이 함정의 출처다(AcademicProgramMineRole 주석) — 응답의 isLeader는 리더
     * 본인만 참이라 mine=true 결과의 길이로 "스터디장인가"를 판정하면 제출자까지 통과한다.
     * 조각을 여기 두는 것은 JPQL 별칭(l·a)에 매인 문자열이라 dto가 알 값이 아니기 때문이다
     * (AcademicProgramSortOrder가 정렬 키만 갖고 경로는 이 클래스가 정하는 것과 같다).
     */
    private static final String LEADER_IS_MINE = "l.id = :mineId";
    private static final String PROPOSER_IS_MINE = "a.proposer.id = :mineId";

    private static final String CREATED_AT_PATH = "a.createdAt";
    private static final String EVENT_BGNG_DT_PATH = "e.beginAt";

    private final EntityManager entityManager;

    @Override
    public List<AcademicProgramEntity> search(AcademicProgramSearchQuery query) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        String jpql =
                SELECT_ROWS
                        + whereClause(conditions(query, parameters, true))
                        + orderBy(query.sort());

        TypedQuery<AcademicProgramEntity> typedQuery =
                entityManager.createQuery(jpql, AcademicProgramEntity.class);
        parameters.forEach(typedQuery::setParameter);
        // 다음 페이지 존재 여부를 알기 위해 한 건 더 읽는다
        return typedQuery.setMaxResults(query.fetchSize()).getResultList();
    }

    // 커서·정렬은 건수와 무관하므로 빼고 센다
    @Override
    public long countMatching(AcademicProgramSearchQuery query) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        String jpql = SELECT_COUNT + whereClause(conditions(query, parameters, false));

        TypedQuery<Long> typedQuery = entityManager.createQuery(jpql, Long.class);
        parameters.forEach(typedQuery::setParameter);
        return typedQuery.getSingleResult();
    }

    private String whereClause(List<String> conditions) {
        return conditions.isEmpty() ? "" : " where " + String.join(" and ", conditions);
    }

    /*
     * 값이 없는 필터는 조건 자체를 붙이지 않는다 — ':param is null or ...' 형태로 항상 붙이면
     * 옵티마이저가 인덱스를 못 쓴다(WorkRepositoryImpl과 같은 이유).
     */
    private List<String> conditions(
            AcademicProgramSearchQuery query, Map<String, Object> parameters, boolean withCursor) {
        List<String> conditions = new ArrayList<>();

        if (query.hasStatusFilter()) {
            conditions.add("a.status = :status");
            parameters.put("status", query.status());
        }
        if (query.hasTypeFilter()) {
            conditions.add("t.code = :typeCd");
            parameters.put("typeCd", query.typeCd());
        }
        if (query.hasKeywordFilter()) {
            conditions.add("lower(e.title) like :keyword escape '" + LIKE_ESCAPE + "'");
            parameters.put("keyword", likePattern(query.keyword()));
        }
        if (query.hasMineFilter()) {
            conditions.add(mineCondition(query.mineRole()));
            parameters.put("mineId", query.mine().getId());
        }
        if (withCursor && query.hasCursor()) {
            conditions.add(cursorCondition(query, parameters));
        }
        return conditions;
    }

    // 역할별 조건절. ANY는 지금까지의 동작(스터디장 OR 제출자)이며 mine=true가 그것이다
    private String mineCondition(AcademicProgramMineRole role) {
        return switch (role) {
            case LEADER -> LEADER_IS_MINE;
            case PROPOSER -> PROPOSER_IS_MINE;
            case ANY -> "(" + LEADER_IS_MINE + " or " + PROPOSER_IS_MINE + ")";
        };
    }

    /*
     * 커서보다 뒤에 있는 건들. 정렬 키가 같은 건이 여럿일 수 있어 식별자로 동률을 끊는다.
     * 두 정렬 키 모두 NULL일 수 없으므로(AcademicProgramSortOrder 주석) work 목록의
     * nulls last 처리는 필요 없다 — member 목록과 같은 처지다.
     */
    private String cursorCondition(
            AcademicProgramSearchQuery query, Map<String, Object> parameters) {
        AcademicProgramCursor cursor = query.cursor();
        String path = sortPath(query.sort());
        parameters.put("cursorId", cursor.academicProgramId());
        parameters.put("cursorKey", cursor.sortValue());

        String comparison = query.sort().isDescending() ? "<" : ">";
        return "("
                + path
                + " "
                + comparison
                + " :cursorKey or ("
                + path
                + " = :cursorKey and a.id > :cursorId))";
    }

    /*
     * 동률 정렬은 방향과 무관하게 항상 식별자 오름차순이다. 커서 비교식도 같은 방향을 쓰므로
     * 둘을 함께 바꾸지 않는 한 여기만 뒤집으면 안 된다.
     */
    private String orderBy(AcademicProgramSortOrder sort) {
        String direction = sort.isDescending() ? "desc" : "asc";
        return " order by " + sortPath(sort) + " " + direction + ", a.id asc";
    }

    private String sortPath(AcademicProgramSortOrder sort) {
        return sort.getKey() == AcademicProgramSortOrder.SortKey.EVENT_BGNG_DT
                ? EVENT_BGNG_DT_PATH
                : CREATED_AT_PATH;
    }

    /*
     * 부분일치 패턴. 와일드카드와 이스케이프 문자 자신을 먼저 막아야 검색어가 질의 문법으로
     * 새어 들어가지 않는다(MemberRepositoryImpl.likePattern과 같은 로직).
     */
    private String likePattern(String keyword) {
        String escaped =
                keyword.replace(LIKE_ESCAPE, LIKE_ESCAPE + LIKE_ESCAPE)
                        .replace("%", LIKE_ESCAPE + "%")
                        .replace("_", LIKE_ESCAPE + "_");
        return "%" + escaped.toLowerCase(Locale.ROOT) + "%";
    }
}
