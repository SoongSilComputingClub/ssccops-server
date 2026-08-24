package org.sscc.ssccopsserver.domain.academicprogram.repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import org.sscc.ssccopsserver.domain.academicprogram.dto.AcademicProgramApprovalSearchQuery;
import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramApprovalEntity;

import lombok.RequiredArgsConstructor;

/*
 * 승인 이력 목록(#139)의 동적 쿼리 구현. SessionRepositoryImpl의 골격을 그대로 따른다 —
 * 조건을 문자열로 이어붙이지 않고 리스트로 모았다가 있을 때만 where를 붙이고, 요청 값은 예외
 * 없이 이름 있는 파라미터로만 바인딩한다.
 *
 * 승인자는 fetch join으로 함께 읽는다 — 응답이 aprvrMbrNm을 실으므로 그러지 않으면 줄마다
 * 회원 조회가 따라붙는다. 회차는 SESSION 지점에만 있는 nullable 연관이라 left join이며,
 * 식별자만 쓰는데도 함께 읽는 것은 nullable to-one이 프록시로 미뤄지지 않아 어차피 줄마다
 * 질의가 나가기 때문이다. 그래서 이 API의 질의는 이력이 몇 건이든 목록 1 + 필터 건수 1 +
 * 전체 건수 1로 고정된다(DB-13).
 */
@RequiredArgsConstructor
public class AcademicProgramApprovalRepositoryImpl
        implements AcademicProgramApprovalRepositoryCustom {

    private static final String SELECT_ROWS =
            "select a from AcademicProgramApprovalEntity a"
                    + " join fetch a.approver r"
                    + " left join fetch a.session s";

    // 건수 질의는 fetch 없이 별칭 하나만 세운다. 조건이 참조하는 것은 전부 FK 컬럼이라 조인이 없다
    private static final String SELECT_COUNT =
            "select count(a) from AcademicProgramApprovalEntity a";

    /*
     * 처리 최신순 하나뿐이다. 정렬 키가 곧 식별자라 동률을 끊을 두 번째 키가 필요 없고, 커서
     * 비교식도 같은 컬럼 하나로 끝난다(AcademicProgramApprovalCursor 주석).
     */
    private static final String ORDER_BY = " order by a.id desc";

    private final EntityManager entityManager;

    @Override
    public List<AcademicProgramApprovalEntity> search(AcademicProgramApprovalSearchQuery query) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        String jpql = SELECT_ROWS + whereClause(conditions(query, parameters, true)) + ORDER_BY;

        TypedQuery<AcademicProgramApprovalEntity> typedQuery =
                entityManager.createQuery(jpql, AcademicProgramApprovalEntity.class);
        parameters.forEach(typedQuery::setParameter);
        // 다음 페이지 존재 여부를 알기 위해 한 건 더 읽는다
        return typedQuery.setMaxResults(query.fetchSize()).getResultList();
    }

    // 커서·정렬은 건수와 무관하므로 빼고 센다
    @Override
    public long countMatching(AcademicProgramApprovalSearchQuery query) {
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
     * 활동으로 좁히는 조건은 필터가 아니라 범위라 언제나 붙는다
     * (AcademicProgramApprovalSearchQuery 주석).
     */
    private List<String> conditions(
            AcademicProgramApprovalSearchQuery query,
            Map<String, Object> parameters,
            boolean withCursor) {
        List<String> conditions = new ArrayList<>();

        conditions.add("a.academicProgram.id = :academicProgramId");
        parameters.put("academicProgramId", query.academicProgramId());

        if (query.hasPointFilter()) {
            conditions.add("a.point = :point");
            parameters.put("point", query.point());
        }
        if (query.hasSessionFilter()) {
            conditions.add("a.session.id = :sessionId");
            parameters.put("sessionId", query.sessionId());
        }
        if (withCursor && query.hasCursor()) {
            conditions.add("a.id < :cursorId");
            parameters.put("cursorId", query.cursor().approvalId());
        }
        return conditions;
    }
}
