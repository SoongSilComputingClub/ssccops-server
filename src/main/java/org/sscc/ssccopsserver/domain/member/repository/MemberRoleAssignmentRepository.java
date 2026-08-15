package org.sscc.ssccopsserver.domain.member.repository;

import java.time.LocalDate;
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
}
