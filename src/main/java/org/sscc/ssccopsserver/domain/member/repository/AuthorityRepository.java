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
}
