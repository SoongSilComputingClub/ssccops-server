package org.sscc.ssccopsserver.domain.member.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.sscc.ssccopsserver.domain.member.entity.AuthorityEntity;
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

    /*
     * 한 역할의 부여 전부 (#65). 권한명을 응답에 실으므로 join fetch로 함께 가져온다 —
     * 없으면 부여 건수만큼 추가 쿼리가 나간다.
     */
    @Query(
            "select r from RoleAuthorityRelationEntity r join fetch r.authority a"
                    + " where r.role.id = :roleId order by a.code asc")
    List<RoleAuthorityRelationEntity> findAllByRoleId(@Param("roleId") Long roleId);

    /** 어느 역할엔가 부여돼 있는지. 부여된 권한은 지울 수 없으므로 삭제 전에 본다 (#65) */
    boolean existsByAuthority(AuthorityEntity authority);
}
