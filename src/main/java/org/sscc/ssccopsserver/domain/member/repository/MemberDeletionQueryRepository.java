package org.sscc.ssccopsserver.domain.member.repository;

import java.util.List;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;

/*
 * 회원 삭제 미리보기의 질의 두 개 (#361 · ADR-0021). 둘 다 native SQL이다.
 *
 * ── 왜 다른 도메인의 Repository를 주입받지 않는가 ────────────────
 * 미리보기가 세는 것은 폼 응답·행사 참가·운영 업무·학술 활동… 스무 곳이 넘는 남의 테이블이다.
 * 그쪽 Repository를 주입받으면 member → form·event·operation·academicprogram·share·meeting으로
 * 화살표가 생기고, 그 도메인들은 전부 이미 member를 가리키므로 **전부 순환**이다
 * (DomainCycleTest · ssccops#242). 포트 인터페이스(`MemberSubWorkLoadProvider`처럼 회원이
 * 선언하고 소유 도메인이 구현)로 풀 수도 있지만 그러려면 포트 여섯 개에 구현 빈 여섯 개가
 * 필요하고, 그 스무 개가 «무엇인가»는 이미 FK 이름으로 DB에 못 박혀 있다. 이 기능은 임시이며
 * (플래그로 닫는다) 그 표는 MemberReferenceConstraints 하나면 된다 — SQL 문자열은 자바
 * 의존이 아니라 DomainCycleTest에 걸리지 않고, 표가 곧 질의라 두 벌이 될 자리가 없다.
 *
 * ── 왜 EntityManager인가 ──────────────────────────────────────
 * 21개 SELECT를 UNION ALL로 이은 한 문장이라 Spring Data 파생 메서드로 표현되지 않고, 한 행에
 * 집계 셋을 싣는 질의는 @Query(nativeQuery) 프로젝션이 H2(대문자 별칭)와 PostgreSQL(소문자
 * 별칭)에서 컬럼 이름 대응이 갈린다. 직접 만들면 결과를 위치로 읽으므로 그 차이가 없다.
 *
 * 문법은 H2와 PostgreSQL이 함께 받는 것만 쓴다 — 스칼라 서브쿼리와 DISTINCT 상수뿐이다.
 * LIMIT·FETCH FIRST·FROM 없는 SELECT·파생 테이블 위의 boolean 필터를 피한 것도 그래서다
 * (BLOCKING_SQL 주석).
 *
 * Hibernate 6은 native 질의 앞에 세션을 flush 한다(어느 테이블이 걸리는지 모르므로 전부) —
 * 같은 트랜잭션에서 방금 저장한 응답도 셈에 든다.
 */
@Repository
public class MemberDeletionQueryRepository {

    private static final String COUNT_SQL =
            "SELECT"
                    + " (SELECT count(*) FROM form_rspns_hstry WHERE mbr_id = :memberId),"
                    + " (SELECT count(*) FROM event_ptcp WHERE mbr_id = :memberId),"
                    + " (SELECT count(*) FROM mbr_grd_hstry WHERE mbr_id = :memberId)"
                    + " + (SELECT count(*) FROM mbr_stts_hstry WHERE mbr_id = :memberId)"
                    + " + (SELECT count(*) FROM mbr_chg_hstry WHERE mbr_id = :memberId)";

    /*
     * `SELECT DISTINCT '제약이름' FROM … WHERE … UNION ALL …` — 가리키는 행이 하나라도 있는 참조의
     * 키만 남는다. 키가 제약 이름인 것은 409의 번역과 같은 표를 쓰기 위해서다 — 삭제가 실제로
     * 막혔을 때 나오는 문구와 미리보기의 blockedBy가 한 글자도 다르지 않아야 한다.
     *
     * EXISTS를 파생 테이블로 감싸 `WHERE hit`로 거르는 모양이 더 곧지만 H2가 그 조건을 각
     * 분기로 밀어 넣으며 문법이 깨진 SQL을 만들었다(`EXISTS(...) IS NOT DISTINCT FROM ?`). DISTINCT
     * 상수는 표준 SQL 그대로라 두 DB에서 같은 계획으로 돈다 — 참조 행이 많아도 인덱스 범위 조회
     * 하나이고, 이 표의 테이블은 동아리 규모다.
     */
    private static final String BLOCKING_SQL =
            MemberReferenceConstraints.BLOCKING.stream()
                    .map(r -> "SELECT DISTINCT '" + r.constraintName() + "' " + r.matchSql())
                    .collect(Collectors.joining(" UNION ALL "));

    /** 함께 지워질 것의 건수 — 응답·참가·이력(등급+상태+정보 변경) */
    public record DeletionCounts(long responseCount, long participationCount, long historyCount) {}

    @PersistenceContext private EntityManager entityManager;

    public DeletionCounts countOwnData(Long memberId) {
        Object[] row =
                (Object[])
                        entityManager
                                .createNativeQuery(COUNT_SQL)
                                .setParameter("memberId", memberId)
                                .getSingleResult();
        return new DeletionCounts(toLong(row[0]), toLong(row[1]), toLong(row[2]));
    }

    /** 삭제를 막을 참조의 **제약 이름** 목록. MemberReferenceConstraints.BLOCKING 순서다 */
    @SuppressWarnings("unchecked")
    public List<String> findBlockingConstraintNames(Long memberId) {
        return (List<String>)
                entityManager
                        .createNativeQuery(BLOCKING_SQL)
                        .setParameter("memberId", memberId)
                        .getResultList();
    }

    // count(*)는 H2·PostgreSQL 모두 bigint지만 드라이버가 돌려주는 자바 타입까지 못 박지는 않는다
    private static long toLong(Object value) {
        return ((Number) value).longValue();
    }
}
