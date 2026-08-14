package org.sscc.ssccopsserver.domain.member.repository;

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
}
