package org.sscc.ssccopsserver.domain.member.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.sscc.ssccopsserver.domain.member.entity.AuthorityEntity;

public interface AuthorityRepository extends JpaRepository<AuthorityEntity, String> {

    /*
     * 권한 트리의 간선 전부. 최상위 권한도 빠지지 않도록 left join이다 —
     * inner join(a.parent.code)으로 쓰면 부모가 없는 EXECUTIVE가 통째로 사라져
     * 그 아래 자손이 하나도 펼쳐지지 않는다.
     *
     * 권한은 수십 건 규모라 전량을 받아 메모리에서 펼친다. 재귀 CTE를 쓰지 않는 것은
     * H2(test)와 PostgreSQL 양쪽에서 같은 SQL을 보장하기 어렵고, 판정 규칙이 SQL로 흩어지면
     * capabilities 계산과 갈릴 여지가 생기기 때문이다.
     */
    @Query(
            "select new org.sscc.ssccopsserver.domain.member.repository.AuthorityLink("
                    + "a.code, p.code) from AuthorityEntity a left join a.parent p")
    List<AuthorityLink> findAllLinks();

    /*
     * 트리 조립용 전량 조회 (#65).
     *
     * 형제 사이의 순서는 indct_seqno이고, 같은 순번이면 코드로 끊는다 — 시드가 형제마다 1부터
     * 매기므로 부모가 다르면 순번이 겹치는 것이 정상이고, 정렬을 순번 하나로만 두면 같은 부모
     * 아래에서 순서가 요청마다 달라져 화면의 트리가 흔들린다.
     *
     * 상위(parent)를 join fetch 하는 것은 응답에 upAuthrtCd를 싣기 때문이다. 없으면 노드마다
     * 프록시를 깨우는 쿼리가 붙는다.
     */
    @Query(
            "select a from AuthorityEntity a left join fetch a.parent"
                    + " order by a.displayOrder asc, a.code asc")
    List<AuthorityEntity> findAllForTree();

    /** 자식이 하나라도 있는지. 부모를 지우면 자식이 갈 곳을 잃으므로 삭제 전에 본다 (#65) */
    boolean existsByParent(AuthorityEntity parent);
}
