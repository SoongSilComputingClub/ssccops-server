package org.sscc.ssccopsserver.domain.member.dto;

import org.sscc.ssccopsserver.domain.member.entity.MemberRoleAssignmentEntity;

/*
 * 회원이 현재 맡고 있는 조직 역할(회장·부회장·국장 등) 한 건.
 *
 * 종료일이 지난 역할은 담지 않는다 — '현재 역할'만 화면에 쓰이기 때문이다.
 * representative는 여러 현재 역할 중 사이드바 프로필에 대표로 표시할 하나를 가린다.
 *
 * 이 목록은 **표시용**이다. 인가 판정은 역할이 아니라 그 역할에 부여된 권한으로 하며(#9),
 * 화면이 버튼을 감출 때 쓰는 값은 여기가 아니라 MemberProfileResponse.capabilities다.
 * 예외였던 직위 코드(rolePstnCd, #118)는 승인·투표 자격이 권한 시스템으로 통합되며
 * 사라졌다(#123) — 이제 이 목록에는 판정에 쓰이는 값이 하나도 없다.
 */
public record MemberRoleResponse(Long roleId, String roleName, boolean representative) {

    public static MemberRoleResponse from(MemberRoleAssignmentEntity assignment) {
        return new MemberRoleResponse(
                assignment.getRole().getId(),
                assignment.getRole().getName(),
                Boolean.TRUE.equals(assignment.getRepresentative()));
    }
}
