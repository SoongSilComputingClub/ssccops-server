package org.sscc.ssccopsserver.domain.member.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

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
     * findValidRoleIds의 반대 방향(#101) — 주어진 역할들 중 하나라도 오늘 유효하게 배정된
     * 회원의 id 전부. "이 권한을 가진 회원은 누구인가"(AuthorityPolicy.memberIdsWithAuthority)
     * 조회의 마지막 단계에 쓴다. 판정 기준은 BR-M25로 다른 '유효 역할' 질의들과 같다.
     */
    @Query(
            "select distinct a.member.id from MemberRoleAssignmentEntity a"
                    + " where a.role.id in :roleIds and a.roleStartDate <= :today"
                    + " and (a.roleEndDate is null or a.roleEndDate >= :today)")
    List<Long> findMemberIdsByRoleIdsValidOn(
            @Param("roleIds") Collection<Long> roleIds, @Param("today") LocalDate today);

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
     * 이 회원이 지금 쥐고 있는 역할의 건수 (#78 탈퇴·제명 전이의 경고).
     *
     * 기준은 findValidRoleIds·findValidByMemberIds와 같은 BR-M25다 — role_bgng_ymd <= 오늘 <=
     * role_end_ymd이며 종료일이 NULL이면 무기한이고, 오늘은 주입된 Clock에서 온다. 회원 상세가
     * roles로 그리는 것과 같은 집합이어야 "역할 2건이 있습니다"라는 경고를 보고 상세를 열었을
     * 때 숫자가 맞는다.
     *
     * 목록을 받아 세지 않고 count로 두는 것은 경고가 숫자 하나만 쓰기 때문이다.
     */
    @Query(
            "select count(a) from MemberRoleAssignmentEntity a"
                    + " where a.member.id = :memberId and a.roleStartDate <= :today"
                    + " and (a.roleEndDate is null or a.roleEndDate >= :today)")
    long countCurrentByMemberId(@Param("memberId") Long memberId, @Param("today") LocalDate today);

    /*
     * 이 역할에 배정된 적이 **한 번이라도** 있는가 (#79 삭제 가드).
     *
     * 위의 질의들과 달리 기간을 보지 않는다. 종료된 배정을 빼면 "작년 국장이 누구였는지"가
     * 삭제 한 번으로 사라지기 때문이다 — 지울 수 있는 역할은 아무에게도 붙은 적 없는 역할뿐이다.
     */
    boolean existsByRoleId(Long roleId);

    /*
     * 여러 회원의 현재 역할을 **한 번에** 모아 온다 (#76). 회원 목록·상세가 역할을 함께
     * 표시하므로, 회원마다 findCurrentByMemberId를 부르면 그대로 N+1이 된다 (DB-13 ·
     * 폼 응답 목록 #37의 선례). 이번 페이지에 실린 식별자만 넘긴다.
     *
     * 판정 규칙은 findValidRoleIds와 같은 BR-M25다 — role_bgng_ymd <= 오늘 <= role_end_ymd이며
     * 종료일이 NULL이면 무기한이고, 오늘은 주입된 Clock에서 온다. 화면용 findCurrentByMemberId가
     * '종료일이 비어 있는 것만' 보는 것과 갈리는데, 그쪽 기준으로는 종료일이 미래로 채워진
     * 배정(임기가 정해진 국장 등)이 아직 유효한데도 목록에서 사라진다. 이슈 #76이 BR-M25를
     * 명시하고 있어 새 조회 경로는 인가 판정과 같은 규칙을 쓴다.
     *
     * 역할명을 함께 쓰므로 join fetch로 가져온다 — 없으면 배정 건수만큼 추가 쿼리가 나간다.
     * rprs_role_yn은 정렬·판정에 쓰지 않는다 (BR-M26). 표시용으로만 응답에 실린다.
     */
    @Query(
            "select a from MemberRoleAssignmentEntity a join fetch a.role"
                    + " where a.member.id in :memberIds and a.roleStartDate <= :today"
                    + " and (a.roleEndDate is null or a.roleEndDate >= :today)"
                    + " order by a.member.id asc, a.role.displayOrder asc, a.role.id asc")
    List<MemberRoleAssignmentEntity> findValidByMemberIds(
            @Param("memberIds") Collection<Long> memberIds, @Param("today") LocalDate today);

    /*
     * 회원의 역할 배정 **전부** (#81 GET .../roles, current=false).
     *
     * 위의 질의들과 달리 기간을 보지 않는다 — 종료된 배정도 목록에 남아야 "언제까지 국장이었는가"를
     * 화면에서 볼 수 있고, 그것이 '종료는 삭제가 아니다'라는 규칙이 실제로 쓰이는 자리다.
     * current=true 목록은 이 질의가 아니라 findValidByMemberIds(BR-M25)를 그대로 쓴다.
     *
     * 정렬은 시작일 내림차순 → 식별자 내림차순이다. 최근 임기가 위에 서야 하고, 같은 날 시작한
     * 배정은 식별자로 끊어야 목록을 두 번 불러도 순서가 흔들리지 않는다.
     *
     * 통합 이력 조회(#82)도 역할 재료를 이 질의로 받는다 — 이력에는 종료된 임기가 반드시
     * 실려야 하므로 기간을 보지 않는 이 질의가 그대로 맞고, 새 질의를 하나 더 두면 '어떤
     * 배정이 이력에 실리는가'가 두 곳에서 정해진다. 합쳐 정렬하는 것은 서비스의 몫이다.
     */
    @Query(
            "select a from MemberRoleAssignmentEntity a join fetch a.role"
                    + " where a.member.id = :memberId"
                    + " order by a.roleStartDate desc, a.id desc")
    List<MemberRoleAssignmentEntity> findAllByMemberId(@Param("memberId") Long memberId);

    /*
     * 배정 한 건을 **회원 범위 안에서만** 찾는다 (#81 PATCH).
     *
     * 경로에 회원과 배정이 둘 다 있는데 배정 식별자만 보면 /v1/members/1/roles/999가 다른 회원의
     * 역할을 종료시킨다 — 폼 응답의 findByIdAndForm(#37)과 같은 자리다. 없는 배정과 남의 배정은
     * 같은 404로 돌아간다.
     *
     * 응답에 역할명을 실으므로 join fetch로 함께 가져온다.
     */
    @Query(
            "select a from MemberRoleAssignmentEntity a join fetch a.role"
                    + " where a.id = :assignmentId and a.member.id = :memberId")
    Optional<MemberRoleAssignmentEntity> findByIdAndMemberId(
            @Param("assignmentId") Long assignmentId, @Param("memberId") Long memberId);

    /*
     * 같은 역할이 기간을 겹쳐 이미 부여돼 있는가 (#81 · 409 ROLE_ALREADY_ASSIGNED).
     *
     * **새 배정은 언제나 [startDate, 무기한)이다** — 부여 요청이 종료일을 받지 않기 때문이다
     * (MemberRoleAssignRequest 주석). 그래서 겹침 조건의 절반(기존 시작일 <= 새 종료일)이 항상
     * 참이 되어 남는 것은 '기존 배정이 새 시작일 이후까지 살아 있는가' 하나뿐이다. 종료일이
     * NULL인 배정은 무기한이므로 어떤 시작일과도 겹친다.
     *
     * 조건을 [startDate, endDate] 양쪽으로 일반화하지 않은 것은 endDate에 NULL을 넘기는 순간
     * `:endDate is null` 분기가 필요해지고, 그런 질의는 Hibernate가 파라미터 타입을 추론하지 못해
     * 깨지는 자리이기 때문이다(폼 목록의 상태 필터 #32와 같은 사정). 필요해지면 그때 연다.
     */
    @Query(
            "select count(a) > 0 from MemberRoleAssignmentEntity a"
                    + " where a.member.id = :memberId and a.role.id = :roleId"
                    + " and (a.roleEndDate is null or a.roleEndDate >= :startDate)")
    boolean existsOverlappingAssignment(
            @Param("memberId") Long memberId,
            @Param("roleId") Long roleId,
            @Param("startDate") LocalDate startDate);

    /*
     * 지금 유효한 대표 역할 배정 (#81 · BR-M26).
     *
     * 대표는 **회원당 유효한 것 중 최대 1건**이므로 새 대표를 지정할 때 여기서 찾은 행을 같은
     * 트랜잭션에서 내린다. 유효 판정은 findValidRoleIds와 같은 BR-M25다 — 이미 끝난 임기에
     * 남아 있는 rprs_role_yn까지 내리면 지난 이력이 조작 한 번으로 바뀐다.
     *
     * 하나만 돌려주지 않고 목록인 것은 이 API 이전에 만들어진 데이터에 대표가 둘 이상 있을 수
     * 있어서다. 한 건만 내리면 나머지가 남아 단일성이 영영 회복되지 않는다.
     */
    @Query(
            "select a from MemberRoleAssignmentEntity a"
                    + " where a.member.id = :memberId and a.representative = true"
                    + " and a.roleStartDate <= :today"
                    + " and (a.roleEndDate is null or a.roleEndDate >= :today)")
    List<MemberRoleAssignmentEntity> findValidRepresentatives(
            @Param("memberId") Long memberId, @Param("today") LocalDate today);
}
