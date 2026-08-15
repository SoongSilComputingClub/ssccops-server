package org.sscc.ssccopsserver.domain.member.repository;

import java.util.List;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleClassificationEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleEntity;

public interface MemberRoleRepository extends JpaRepository<MemberRoleEntity, Long> {

    /*
     * 이름으로 역할을 찾으면서 그 행을 잠근다 (SELECT ... FOR UPDATE).
     *
     * 최초 가입자 부트스트랩(#71)이 쓰는 유일한 경로다. 잠금이 필요한 이유는 판정 대상이
     * '회원이 한 명도 없는가'라는 **다른 테이블의 상태**여서, 그것만으로는 동시 가입 두 건을
     * 막을 장치가 없기 때문이다 — 둘 다 빈 테이블을 보고 둘 다 최고관리자가 된다(VR-M14).
     * 부트스트랩 역할 행 하나를 합류 지점으로 삼아 두 요청을 줄 세운다.
     *
     * 호출부는 잠금을 잡은 **뒤에** 회원 수를 다시 세야 한다. 순서를 뒤집으면 잠금을 기다리는
     * 동안 앞선 트랜잭션이 커밋해 버려 이미 낡은 숫자를 손에 쥔 채로 통과한다.
     *
     * role_nm은 UNIQUE가 아니므로(data.sql 참고) 목록으로 받는다. 같은 이름의 역할이 여럿이면
     * role_id가 가장 작은 것 — 시드가 넣은 원본 — 이 앞에 온다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from MemberRoleEntity r where r.name = :name order by r.id asc")
    List<MemberRoleEntity> findAllByNameForUpdate(@Param("name") String name);

    /*
     * 분류별 소속 역할 수(#80 역할 분류 목록). 분류마다 count를 부르면 그대로 N+1이 되므로
     * GROUP BY 한 번으로 받아 호출부에서 분류별로 나눈다 (DB-13).
     *
     * 분류 식별자를 인자로 받지 않는 것은 목록이 언제나 role_clsf 전량이기 때문이다 — 필터가
     * 없는데 in 절에 모든 코드를 실어 보내면 하는 일은 같고 바인딩만 는다.
     *
     * 조인이 아니라 FK 컬럼(r.roleClassification.code)으로 묶는다. 연관을 타고 들어가면
     * role_clsf를 한 번 더 읽는데 이름은 호출부가 이미 들고 있다.
     */
    @Query(
            """
            select r.roleClassification.code as roleClsfCd, count(r) as roleCount
            from MemberRoleEntity r
            group by r.roleClassification.code
            """)
    List<RoleClassificationRoleCount> countRolesByClassification();

    /*
     * 분류 삭제 가드(#80). role.role_clsf_cd가 NOT NULL FK라 소속 역할이 있는 분류를 지우면
     * 역할이 갈 곳을 잃는다 — 목록의 집계와 달리 여기서는 한 분류만 보면 되므로 exists다.
     */
    boolean existsByRoleClassification(MemberRoleClassificationEntity roleClassification);
}
