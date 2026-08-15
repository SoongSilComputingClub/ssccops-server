package org.sscc.ssccopsserver.domain.member.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberStatusHistoryEntity;

public interface MemberStatusHistoryRepository
        extends JpaRepository<MemberStatusHistoryEntity, Long> {

    /*
     * 회원 상세의 '최근 변경' 재료 (#76). 규칙은 MemberGradeHistoryRepository와 같다 —
     * 두 이력을 같은 기준으로 읽어야 섞은 뒤의 순서가 한쪽으로 기울지 않는다.
     */
    @EntityGraph(attributePaths = {"previousStatus", "newStatus", "changedBy"})
    List<MemberStatusHistoryEntity> findByMemberIdOrderByCreatedAtDescIdDesc(
            Long memberId, Pageable pageable);
}
