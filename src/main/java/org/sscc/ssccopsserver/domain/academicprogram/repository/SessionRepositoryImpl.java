package org.sscc.ssccopsserver.domain.academicprogram.repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionCursor;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionSearchQuery;
import org.sscc.ssccopsserver.domain.academicprogram.dto.SessionSortOrder;
import org.sscc.ssccopsserver.domain.academicprogram.entity.SessionEntity;

import lombok.RequiredArgsConstructor;

/*
 * 회차 목록(#135)·활동 횡단 회차 목록(#136)의 동적 쿼리 구현. AcademicProgramRepositoryImpl의
 * 골격을 그대로 따른다 — 조건을 문자열로 이어붙이지 않고 리스트로 모았다가 있을 때만 where를
 * 붙이고, 요청 값은 예외 없이 이름 있는 파라미터로만 바인딩한다.
 *
 * 두 목록은 조건 조립(conditions)·커서 비교식·정렬을 통째로 공유하고 SELECT 절만 다르다.
 * 활동으로 좁히는 조건은 #135에서는 늘 붙고 #136에서는 선택이다(SessionSearchQuery 주석).
 */
@RequiredArgsConstructor
public class SessionRepositoryImpl implements SessionRepositoryCustom {

    /*
     * 목록은 계획(회차 번호·주제·예정일)과 작성자를 함께 내리므로 fetch join으로 한 번에
     * 끌어온다. 건수 질의는 같은 별칭의 일반 join이면 충분하다(AcademicProgramRepositoryImpl과
     * 같은 이유) — 정렬 키가 crclm_artcl에 있어 조인 자체는 양쪽 다 필요하다.
     */
    private static final String SELECT_ROWS =
            "select s from SessionEntity s"
                    + " join fetch s.curriculumItem c"
                    + " join fetch s.registrant r";

    /*
     * 활동 횡단 목록(#136)은 줄마다 활동명(event.event_ttl)과 유형을 보여주고 keyword가 활동
     * 제목을 훑으므로 활동·행사·유형까지 함께 읽는다. 작성자는 이 응답에 없어 끌어오지 않는다.
     */
    private static final String SELECT_CROSS_ROWS =
            "select s from SessionEntity s"
                    + " join fetch s.curriculumItem c"
                    + " join fetch c.academicProgram p"
                    + " join fetch p.event e"
                    + " join fetch p.type t";

    /*
     * 건수 질의는 fetch 없이 같은 별칭만 세운다. 활동·행사까지 조인해 두는 것은 keyword 조건이
     * e를 참조하기 때문이며, 셋 다 NOT NULL FK의 inner join이라 활동 하나짜리 목록의 건수가
     * 이 조인 때문에 달라지지는 않는다.
     */
    private static final String SELECT_COUNT =
            "select count(s) from SessionEntity s"
                    + " join s.curriculumItem c"
                    + " join c.academicProgram p"
                    + " join p.event e";

    private static final String SEQNO_PATH = "c.seqno";
    private static final String REAL_DT_PATH = "s.realDate";

    // like 와일드카드 이스케이프 문자. AcademicProgramRepositoryImpl과 같은 이유로 '!'를 쓴다
    private static final String LIKE_ESCAPE = "!";

    private final EntityManager entityManager;

    @Override
    public List<SessionEntity> search(SessionSearchQuery query) {
        return rows(SELECT_ROWS, query);
    }

    @Override
    public List<SessionEntity> searchCross(SessionSearchQuery query) {
        return rows(SELECT_CROSS_ROWS, query);
    }

    private List<SessionEntity> rows(String selectClause, SessionSearchQuery query) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        String jpql =
                selectClause
                        + whereClause(conditions(query, parameters, true))
                        + orderBy(query.sort());

        TypedQuery<SessionEntity> typedQuery = entityManager.createQuery(jpql, SessionEntity.class);
        parameters.forEach(typedQuery::setParameter);
        // 다음 페이지 존재 여부를 알기 위해 한 건 더 읽는다
        return typedQuery.setMaxResults(query.fetchSize()).getResultList();
    }

    // 커서·정렬은 건수와 무관하므로 빼고 센다
    @Override
    public long countMatching(SessionSearchQuery query) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        String jpql = SELECT_COUNT + whereClause(conditions(query, parameters, false));

        TypedQuery<Long> typedQuery = entityManager.createQuery(jpql, Long.class);
        parameters.forEach(typedQuery::setParameter);
        return typedQuery.getSingleResult();
    }

    @Override
    public long countByAcademicProgramId(Long academicProgramId) {
        return entityManager
                .createQuery(
                        "select count(s) from SessionEntity s"
                                + " where s.curriculumItem.academicProgram.id = :academicProgramId",
                        Long.class)
                .setParameter("academicProgramId", academicProgramId)
                .getSingleResult();
    }

    private String whereClause(List<String> conditions) {
        return conditions.isEmpty() ? "" : " where " + String.join(" and ", conditions);
    }

    private List<String> conditions(
            SessionSearchQuery query, Map<String, Object> parameters, boolean withCursor) {
        List<String> conditions = new ArrayList<>();

        if (query.hasAcademicProgramFilter()) {
            conditions.add("c.academicProgram.id = :academicProgramId");
            parameters.put("academicProgramId", query.academicProgramId());
        }
        if (query.hasStatusFilter()) {
            conditions.add("s.status = :status");
            parameters.put("status", query.status());
        }
        /*
         * 활동명과 회차 주제를 함께 훑는다(#136). 국장이 "무엇을 찾는가"를 미리 나누지 않기
         * 때문이다 — 스터디 이름을 칠 수도, 회차 주제를 칠 수도 있다. 두 필드를 각각의
         * 파라미터로 두지 않는 것은 화면이 검색창을 하나만 두기 때문이다.
         *
         * 이 조건은 활동 횡단 질의(e 별칭이 있는 SELECT)에서만 붙는다 — 활동 하나짜리 목록은
         * keyword를 받지 않으므로(SessionCondition) 여기까지 오지 않는다.
         */
        if (query.hasKeywordFilter()) {
            conditions.add(
                    "(lower(e.title) like :keyword escape '"
                            + LIKE_ESCAPE
                            + "' or lower(c.title) like :keyword escape '"
                            + LIKE_ESCAPE
                            + "')");
            parameters.put("keyword", likePattern(query.keyword()));
        }
        if (withCursor && query.hasCursor()) {
            conditions.add(cursorCondition(query, parameters));
        }
        return conditions;
    }

    /*
     * 커서보다 뒤에 있는 건들. 정렬 키가 같은 건이 여럿일 수 있어 식별자로 동률을 끊는다.
     * 두 정렬 키 모두 NULL일 수 없으므로(SessionSortOrder 주석) nulls last 처리는 필요 없다.
     */
    private String cursorCondition(SessionSearchQuery query, Map<String, Object> parameters) {
        SessionCursor cursor = query.cursor();
        String path = sortPath(query.sort());
        parameters.put("cursorId", cursor.sessionId());
        parameters.put("cursorKey", cursor.typedSortValue());

        String comparison = query.sort().isDescending() ? "<" : ">";
        return "("
                + path
                + " "
                + comparison
                + " :cursorKey or ("
                + path
                + " = :cursorKey and s.id > :cursorId))";
    }

    /*
     * 동률 정렬은 방향과 무관하게 항상 식별자 오름차순이다. 커서 비교식도 같은 방향을 쓰므로
     * 둘을 함께 바꾸지 않는 한 여기만 뒤집으면 안 된다.
     */
    private String orderBy(SessionSortOrder sort) {
        String direction = sort.isDescending() ? "desc" : "asc";
        return " order by " + sortPath(sort) + " " + direction + ", s.id asc";
    }

    private String sortPath(SessionSortOrder sort) {
        return sort.getKey() == SessionSortOrder.SortKey.REAL_DT ? REAL_DT_PATH : SEQNO_PATH;
    }

    /*
     * 부분일치 패턴. 와일드카드와 이스케이프 문자 자신을 먼저 막아야 검색어가 질의 문법으로
     * 새어 들어가지 않는다(AcademicProgramRepositoryImpl.likePattern과 같은 로직).
     */
    private String likePattern(String keyword) {
        String escaped =
                keyword.replace(LIKE_ESCAPE, LIKE_ESCAPE + LIKE_ESCAPE)
                        .replace("%", LIKE_ESCAPE + "%")
                        .replace("_", LIKE_ESCAPE + "_");
        return "%" + escaped.toLowerCase(Locale.ROOT) + "%";
    }
}
