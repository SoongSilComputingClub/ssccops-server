package org.sscc.ssccopsserver.domain.member.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.sscc.ssccopsserver.domain.member.entity.MemberGradeHistoryEntity;

public interface MemberGradeHistoryRepository
        extends JpaRepository<MemberGradeHistoryEntity, Long> {

    /*
     * 회원 상세의 '최근 변경' 재료 (#76). 상태 이력과 섞어 자르므로 여기서도 몇 건만 읽으면
     * 되고, 몇 건을 읽을지는 합치는 쪽(서비스)이 Pageable로 정한다.
     *
     * 정렬은 crt_dt 역순이며 같은 시각의 이력을 식별자로 끊는다 — 한 트랜잭션에서 등급과
     * 상태가 함께 바뀌면 두 행의 crt_dt가 같은 값이 될 수 있어, 동률을 끊지 않으면 요청마다
     * 순서가 흔들린다.
     *
     * 이전·이후 등급과 변경자는 응답에 이름까지 실리므로 함께 끌어온다 (DB-13).
     */
    @EntityGraph(attributePaths = {"previousGrade", "newGrade", "changedBy"})
    List<MemberGradeHistoryEntity> findByMemberIdOrderByCreatedAtDescIdDesc(
            Long memberId, Pageable pageable);

    /*
     * 통합 이력 조회(#82)의 재료 — 이 회원의 등급 이력 **전부**.
     *
     * 자르지 않는 것은 그 목록에 페이징을 두지 않기로 했기 때문이다(근거는
     * MemberHistoryServiceImpl 주석). 정렬 규칙은 위의 최근 3건판과 같은 한 벌이며,
     * 두 경로가 다른 순서를 쓰면 상세의 '최근 변경'과 이력 화면의 첫 줄이 갈린다.
     *
     * **변경자를 함께 끌어오는 것이 N+1 방지다.** 이력마다 chnrg_mbr_id로 회원을 다시 찾으면
     * 이력 건수만큼 쿼리가 늘어나므로, 이름까지 쓰는 연관을 한 번의 조인으로 모아 온다
     * (DB-13). 별도의 회원 배치 조회를 두지 않는 것은 그 쿼리가 이미 하는 일이기 때문이다.
     */
    @EntityGraph(attributePaths = {"previousGrade", "newGrade", "changedBy"})
    List<MemberGradeHistoryEntity> findByMemberIdOrderByCreatedAtDescIdDesc(Long memberId);
}
