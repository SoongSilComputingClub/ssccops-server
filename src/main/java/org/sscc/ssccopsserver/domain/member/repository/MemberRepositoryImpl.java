package org.sscc.ssccopsserver.domain.member.repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import org.sscc.ssccopsserver.domain.member.dto.MemberCursor;
import org.sscc.ssccopsserver.domain.member.dto.MemberSearchQuery;
import org.sscc.ssccopsserver.domain.member.dto.MemberSortOrder;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;

import lombok.RequiredArgsConstructor;

/*
 * 회원 목록 조회(#76)의 동적 쿼리 구현. Spring Data가 이름 규칙으로 찾아 MemberRepository에
 * 합쳐 주므로 별도 등록이 필요 없다.
 *
 * JPQL을 문자열로 조립하지만 조립에 들어가는 조각은 전부 이 클래스의 상수와 enum에서 나온다 —
 * 요청 값은 예외 없이 이름 있는 파라미터로만 바인딩한다 (WorkRepositoryImpl과 같은 규칙).
 *
 * 등급·상태를 join fetch로 함께 끌어오는 것이 이 목록의 N+1 방지선이다. 둘 다 @ManyToOne이라
 * setMaxResults와 함께 써도 안전하다 — Hibernate가 메모리에서 자르는 것은 컬렉션 fetch일
 * 때뿐이다 (DB-14). 현재 역할은 회원당 여러 건이라 여기서 조인하지 않고 서비스가 이번
 * 페이지의 식별자로 한 번에 모아 온다.
 */
@RequiredArgsConstructor
public class MemberRepositoryImpl implements MemberRepositoryCustom {

    private static final String SELECT_ROWS =
            "select m from MemberEntity m"
                    + " join fetch m.membershipGrade join fetch m.membershipStatus";

    private static final String SELECT_COUNT = "select count(m) from MemberEntity m";

    /*
     * like 와일드카드 이스케이프 문자. 역슬래시를 쓰면 JPQL 문자열 안에서 한 번, 자바
     * 리터럴에서 또 한 번 겹쳐 읽기 어려워지므로 '!'를 쓴다. 이 문자가 없으면 검색어에 섞인
     * '%'·'_'가 와일드카드로 살아나 "20_0"이 학번 전체를 긁어 온다.
     */
    private static final String LIKE_ESCAPE = "!";

    private static final String NAME_PATH = "m.name";
    private static final String GENERATION_PATH = "m.generationNumber";
    private static final String JOIN_DATE_PATH = "m.joinDate";
    private static final String UPDATED_AT_PATH = "m.updatedAt";

    private final EntityManager entityManager;

    @Override
    public List<MemberEntity> search(MemberSearchQuery query) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        String jpql =
                SELECT_ROWS
                        + whereClause(conditions(query, parameters, true))
                        + orderBy(query.sort());

        TypedQuery<MemberEntity> typedQuery = entityManager.createQuery(jpql, MemberEntity.class);
        parameters.forEach(typedQuery::setParameter);
        // 다음 페이지 존재 여부를 알기 위해 한 건 더 읽는다
        return typedQuery.setMaxResults(query.fetchSize()).getResultList();
    }

    // 커서·정렬은 건수와 무관하므로 빼고 센다
    @Override
    public long countMatching(MemberSearchQuery query) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        String jpql = SELECT_COUNT + whereClause(conditions(query, parameters, false));

        TypedQuery<Long> typedQuery = entityManager.createQuery(jpql, Long.class);
        parameters.forEach(typedQuery::setParameter);
        return typedQuery.getSingleResult();
    }

    /*
     * mbr에는 소프트 삭제 컬럼이 없어 늘 붙는 기본 조건이 없다. 조건이 하나도 없을 수 있으므로
     * where 자체를 조건 유무로 붙인다 — 'where 1=1'로 시작하면 조건이 없는 목록에도 쓸모없는
     * 술어가 남는다.
     */
    private String whereClause(List<String> conditions) {
        return conditions.isEmpty() ? "" : " where " + String.join(" and ", conditions);
    }

    /*
     * 값이 없는 필터는 조건 자체를 붙이지 않는다 — ':param is null or ...' 형태로 항상 붙이면
     * 옵티마이저가 인덱스를 못 쓴다.
     *
     * 등급·상태는 @ManyToOne의 식별자 비교라 조인이 더 붙지 않는다(FK 컬럼을 그대로 읽는다).
     */
    private List<String> conditions(
            MemberSearchQuery query, Map<String, Object> parameters, boolean withCursor) {
        List<String> conditions = new ArrayList<>();

        if (query.hasKeyword()) {
            // 이름과 학번 중 하나만 맞아도 된다. 학번이 NULL인 졸업 회원은 이름으로만 걸린다
            conditions.add(
                    "(lower(m.name) like :keyword escape '"
                            + LIKE_ESCAPE
                            + "' or lower(m.studentNumber) like :keyword escape '"
                            + LIKE_ESCAPE
                            + "')");
            parameters.put("keyword", likePattern(query.keyword()));
        }
        if (query.hasGradeFilter()) {
            conditions.add("m.membershipGrade.code in :gradeCodes");
            parameters.put("gradeCodes", query.gradeCodes());
        }
        if (query.hasStatusFilter()) {
            conditions.add("m.membershipStatus.code in :statusCodes");
            parameters.put("statusCodes", query.statusCodes());
        }
        if (withCursor && query.hasCursor()) {
            conditions.add(cursorCondition(query, parameters));
        }
        return conditions;
    }

    /*
     * 커서보다 뒤에 있는 회원들. 정렬 키가 같은 회원이 여럿일 수 있어(동명이인·같은 기수)
     * 식별자로 동률을 끊는다.
     *
     * 정렬 키 넷(mbr_nm·gen_no·join_ymd·mdfcn_dt)이 모두 NOT NULL이라 NULL 구간을 따로
     * 다루지 않는다 — 업무 목록이 nulls last를 신경 쓰는 것과 갈리는 지점이다.
     */
    private String cursorCondition(MemberSearchQuery query, Map<String, Object> parameters) {
        MemberCursor cursor = query.cursor();
        String path = sortPath(query.sort());
        parameters.put("cursorId", cursor.memberId());
        parameters.put("cursorKey", cursor.sortValue());

        String comparison = query.sort().isDescending() ? "<" : ">";
        return "("
                + path
                + " "
                + comparison
                + " :cursorKey or ("
                + path
                + " = :cursorKey and m.id > :cursorId))";
    }

    /*
     * 동률 정렬은 방향과 무관하게 항상 식별자 오름차순이다. 커서 비교식도 같은 방향을 쓰므로
     * 둘을 함께 바꾸지 않는 한 여기만 뒤집으면 안 된다.
     */
    private String orderBy(MemberSortOrder sort) {
        String direction = sort.isDescending() ? "desc" : "asc";
        return " order by " + sortPath(sort) + " " + direction + ", m.id asc";
    }

    private String sortPath(MemberSortOrder sort) {
        return switch (sort.getKey()) {
            case NAME -> NAME_PATH;
            case GENERATION -> GENERATION_PATH;
            case JOIN_DATE -> JOIN_DATE_PATH;
            case UPDATED_AT -> UPDATED_AT_PATH;
        };
    }

    /*
     * 부분일치 패턴. 와일드카드와 이스케이프 문자 자신을 먼저 막아야 검색어가 질의 문법으로
     * 새어 들어가지 않는다. 비교는 양쪽 모두 lower()라 영문 학번·이름의 대소문자를 가리지 않는다.
     */
    private String likePattern(String keyword) {
        String escaped =
                keyword.replace(LIKE_ESCAPE, LIKE_ESCAPE + LIKE_ESCAPE)
                        .replace("%", LIKE_ESCAPE + "%")
                        .replace("_", LIKE_ESCAPE + "_");
        return "%" + escaped.toLowerCase(Locale.ROOT) + "%";
    }
}
