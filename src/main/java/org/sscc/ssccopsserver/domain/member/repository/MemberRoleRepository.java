package org.sscc.ssccopsserver.domain.member.repository;

import java.util.List;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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
}
