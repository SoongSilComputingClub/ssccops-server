package org.sscc.ssccopsserver.domain.member.repository;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberChangeHistoryEntity;

public interface MemberChangeHistoryRepository
        extends JpaRepository<MemberChangeHistoryEntity, Long> {

    /*
     * 통합 이력 조회(#82 · #226)의 재료 — 이 회원의 프로필 변경 이력 **전부**.
     *
     * 정렬 규칙은 등급·상태 이력과 같은 한 벌이다(crt_dt 역순, 동률은 식별자로 끊는다). 한 번의
     * 저장이 여러 항목을 바꾸면 그 행들의 crt_dt가 같은 값이 되므로 동률을 끊지 않으면 요청마다
     * 순서가 흔들린다 — 식별자 역순이면 늦게 만들어진 항목이 위에 서고, 만들어지는 순서는
     * MemberChangeField의 선언 순서라 결정적이다.
     *
     * 변경자를 함께 끌어오는 것이 N+1 방지다 (DB-13). 응답에 이름까지 실리므로 이력마다
     * chnrg_mbr_id로 회원을 다시 찾으면 이력 건수만큼 쿼리가 늘어난다.
     *
     * 자르지 않는 것은 통합 이력에 페이징을 두지 않기로 했기 때문이다(근거는
     * MemberHistoryServiceImpl 주석). 회원 상세의 '최근 변경'(#76)은 이 출처를 읽지 않는다 —
     * 근거는 MemberServiceImpl.recentChangesOf 주석에 있다.
     */
    @EntityGraph(attributePaths = {"changedBy"})
    List<MemberChangeHistoryEntity> findByMemberIdOrderByCreatedAtDescIdDesc(Long memberId);
}
