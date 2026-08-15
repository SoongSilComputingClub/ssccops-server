package org.sscc.ssccopsserver.domain.member.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleAssignmentEntity;

public interface MemberRoleAssignmentRepository
        extends JpaRepository<MemberRoleAssignmentEntity, Long> {

    /*
     * 회원의 현재 역할. 종료일이 비어 있는 배정만 현재 유효한 역할로 본다.
     * 역할명을 함께 쓰므로 join fetch로 가져온다 — 없으면 역할 수만큼 추가 쿼리가 나간다.
     */
    @Query(
            "select a from MemberRoleAssignmentEntity a join fetch a.role"
                    + " where a.member.id = :memberId and a.roleEndDate is null")
    List<MemberRoleAssignmentEntity> findCurrentByMemberId(@Param("memberId") Long memberId);

    /*
     * 인가 판정에 쓰는 '유효한 역할'의 식별자 (#9 · BR-M25).
     *
     * 화면용 findCurrentByMemberId(종료일이 비어 있는 것만)와 기준이 다르다 — 여기서는
     * role_bgng_ymd <= 오늘 <= role_end_ymd이며 종료일이 NULL이면 무기한이다. 종료일이
     * 채워져 있어도 아직 지나지 않았으면 유효하고, 시작일이 미래면 아직 유효하지 않다.
     * 오늘 날짜는 주입된 Clock에서 오므로 파라미터로 받는다.
     *
     * rprs_role_yn(대표 역할)은 보지 않는다 (BR-M26) — 표시용이며, 여러 역할 중 하나라도
     * 요구 권한을 만족하면 통과다.
     */
    @Query(
            "select distinct a.role.id from MemberRoleAssignmentEntity a"
                    + " where a.member.id = :memberId and a.roleStartDate <= :today"
                    + " and (a.roleEndDate is null or a.roleEndDate >= :today)")
    List<Long> findValidRoleIds(@Param("memberId") Long memberId, @Param("today") LocalDate today);

    /*
     * 역할별 현재 보유 회원 수를 **한 번에 모아** 센다 (#79 역할 목록).
     *
     * 역할마다 count를 돌리면 역할 수에 비례해 쿼리가 늘어난다. 목록은 역할 조회 1 + 이 집계
     * 1로 끝나야 하며 RoleControllerTest가 그것을 못 박아 둔다.
     *
     * 기준은 findValidRoleIds와 같다 — role_bgng_ymd <= 오늘 <= role_end_ymd, 종료일 NULL이면
     * 무기한. 인가가 보는 '유효한 역할'과 화면이 세는 '지금 이 역할인 사람'이 갈리면 "권한은
     * 있는데 목록에는 0명"이 된다.
     *
     * 한 회원에게 같은 역할이 기간이 겹치게 두 번 배정된 데이터에서도 한 명으로 세도록
     * count(distinct)를 쓴다 — 세는 단위는 배정이 아니라 사람이다.
     */
    @Query(
            "select a.role.id as roleId, count(distinct a.member.id) as memberCount"
                    + " from MemberRoleAssignmentEntity a"
                    + " where a.role.id in :roleIds and a.roleStartDate <= :today"
                    + " and (a.roleEndDate is null or a.roleEndDate >= :today)"
                    + " group by a.role.id")
    List<RoleMemberCount> countCurrentMembersByRoleIds(
            @Param("roleIds") Collection<Long> roleIds, @Param("today") LocalDate today);

    /*
     * 역할 상세의 재임 회원 목록 (#79). 이름·학번을 쓰므로 회원을 join fetch로 함께 가져온다.
     * 정렬은 이름 → 회원 식별자다(동명이인을 식별자로 끊는다).
     */
    @Query(
            "select a from MemberRoleAssignmentEntity a join fetch a.member m"
                    + " where a.role.id = :roleId and a.roleStartDate <= :today"
                    + " and (a.roleEndDate is null or a.roleEndDate >= :today)"
                    + " order by m.name asc, m.id asc")
    List<MemberRoleAssignmentEntity> findCurrentByRoleId(
            @Param("roleId") Long roleId, @Param("today") LocalDate today);

    /*
     * 이 역할에 배정된 적이 **한 번이라도** 있는가 (#79 삭제 가드).
     *
     * 위의 질의들과 달리 기간을 보지 않는다. 종료된 배정을 빼면 "작년 국장이 누구였는지"가
     * 삭제 한 번으로 사라지기 때문이다 — 지울 수 있는 역할은 아무에게도 붙은 적 없는 역할뿐이다.
     */
    boolean existsByRoleId(Long roleId);
}
