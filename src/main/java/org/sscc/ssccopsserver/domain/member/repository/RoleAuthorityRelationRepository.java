package org.sscc.ssccopsserver.domain.member.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.sscc.ssccopsserver.domain.member.entity.RoleAuthorityRelationEntity;

public interface RoleAuthorityRelationRepository
        extends JpaRepository<RoleAuthorityRelationEntity, Long> {

    /*
     * 역할들에 직접 부여된 권한 코드. 자손 펼침은 하지 않는다 — 펼침은 AuthorityPolicy의 몫이고,
     * 여기서 한 번 더 펼치면 규칙이 두 곳에 놓인다.
     *
     * authrt_cd는 FK 컬럼이라 authority 연관을 조인하지 않고도 꺼낼 수 있다.
     */
    @Query("select r.authority.code from RoleAuthorityRelationEntity r where r.role.id in :roleIds")
    List<String> findAuthorityCodesByRoleIds(@Param("roleIds") Collection<Long> roleIds);
}
