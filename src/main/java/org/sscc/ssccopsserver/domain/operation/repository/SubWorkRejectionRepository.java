package org.sscc.ssccopsserver.domain.operation.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
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
}
