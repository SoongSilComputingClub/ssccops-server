package org.sscc.ssccopsserver.domain.member.repository;

import java.util.List;
import java.util.Optional;

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

    /*
     * 한 분류의 소속 역할 수(#80 생성·수정 응답). 관리 화면이 저장 직후에도 "소속 역할 N건"을
     * 그대로 보여주므로 응답에 건수를 다시 실어야 한다 — 목록의 집계를 부르면 한 건을 위해
     * 전량을 GROUP BY 하게 된다.
     */
    long countByRoleClassification(MemberRoleClassificationEntity roleClassification);

    /*
     * 역할 목록 (#79 GET /v1/roles).
     *
     * 분류를 join fetch로 함께 끌어온다 — 응답에 roleClsfNm이 실리므로 없으면 역할 수만큼
     * 분류 조회가 따라붙는다.
     *
     * 정렬은 분류 순번 → 역할 순번 → role_id다. indct_seqno가 분류 안의 표시 순번이라
     * (VR-M11) 분류를 가르지 않고 정렬하면 서로 다른 분류의 1번들이 뒤섞인다. 마지막에
     * role_id를 두는 것은 같은 분류 안에 같은 순번이 여럿일 수 있어서다 — 자동 채번을
     * 쓰더라도 값을 직접 지정하는 길이 열려 있어 순번은 UNIQUE가 아니다.
     */
    @Query(
            "select r from MemberRoleEntity r join fetch r.roleClassification c"
                    + " order by c.displayOrder asc, r.displayOrder asc, r.id asc")
    List<MemberRoleEntity> findAllForList();

    @Query(
            "select r from MemberRoleEntity r join fetch r.roleClassification c"
                    + " where c.code = :roleClsfCd"
                    + " order by r.displayOrder asc, r.id asc")
    List<MemberRoleEntity> findAllForListByClassification(@Param("roleClsfCd") String roleClsfCd);

    /** 단건 조회. 응답에 분류명이 실리므로 목록과 같이 분류를 함께 가져온다 (#79) */
    @Query("select r from MemberRoleEntity r join fetch r.roleClassification where r.id = :roleId")
    Optional<MemberRoleEntity> findByIdWithClassification(@Param("roleId") Long roleId);

    /*
     * 이름 중복 검사 (#79). role_nm에 UNIQUE 제약이 없어 애플리케이션이 판정한다 —
     * 근거는 RoleServiceImpl의 주석에 있다.
     */
    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);

    /*
     * 같은 분류 안의 최대 표시 순번 (#79 indct_seqno 자동 채번).
     *
     * 분류로 좁히지 않으면 '프로젝트' 분류의 새 역할이 '직책'의 순번 뒤로 밀려 분류마다 1부터
     * 다시 시작한다는 규칙이 깨진다. 그 분류에 역할이 하나도 없으면 0이라 첫 역할은 1이 된다.
     */
    @Query(
            "select coalesce(max(r.displayOrder), 0) from MemberRoleEntity r"
                    + " where r.roleClassification.code = :roleClsfCd")
    int findMaxDisplayOrderByClassification(@Param("roleClsfCd") String roleClsfCd);
}
