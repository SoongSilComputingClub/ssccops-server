package org.sscc.ssccopsserver.domain.operation.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkRejectionEntity;

public interface SubWorkRejectionRepository extends JpaRepository<SubWorkRejectionEntity, Long> {

    // 반려 → 보완 → 재검토요청 → 재반려가 가능해 하위 업무당 여러 건이 쌓인다
    List<SubWorkRejectionEntity> findBySubWorkOrderByRejectedAtAsc(SubWorkEntity subWork);

    /*
     * 상세 화면이 보여줄 직전 반려 (OPS-009 · #58). 같은 트랜잭션에서 두 건이 같은 시각에
     * 기록될 수 있으므로 식별자로 동률을 끊는다 — 시각만으로 정렬하면 어느 건이 최신인지
     * 실행할 때마다 달라진다.
     */
    Optional<SubWorkRejectionEntity> findFirstBySubWorkOrderByRejectedAtDescIdDesc(
            SubWorkEntity subWork);

    /*
     * 승인함(OPS-017)이 카드마다 그리는 반려 사유를 목록 전체에 대해 한 번에 모아 온다 (#62).
     * 위 단건 조회를 카드마다 부르면 그대로 N+1이다 (DB-13).
     *
     * 반려 사유(rjct_rsn)만 있으면 되므로 반려자까지 끌어오지 않는다 — 목록은 사유 한 줄만
     * 그리는데 연관을 열면 카드 수만큼 회원 조회가 따라붙는다 (상세는 반려자를 함께 내린다).
     *
     * 같은 시각에 기록된 반려가 둘이면 이 조회는 두 행을 돌려준다. 식별자로 동률을 끊는 일은
     * 호출부가 한다 — JPQL로는 (일시, 식별자) 쌍 비교를 이식성 있게 쓸 수 없다.
     *
     * 빈 컬렉션을 넘기면 IN () 이 되어 DB에 따라 문법 오류가 나므로 호출 전에 걸러야 한다.
     */
    @Query(
            "select s.id as subWorkId,"
                    + " r.id as rejectionId,"
                    + " r.reason as reason"
                    + " from SubWorkRejectionEntity r"
                    + " join r.subWork s"
                    + " where s.id in :subWorkIds"
                    + " and r.rejectedAt ="
                    + " (select max(r2.rejectedAt) from SubWorkRejectionEntity r2"
                    + " where r2.subWork = s)")
    List<SubWorkLatestRejection> findLatestRejectionsBySubWorkIds(
            @Param("subWorkIds") Collection<Long> subWorkIds);
}
