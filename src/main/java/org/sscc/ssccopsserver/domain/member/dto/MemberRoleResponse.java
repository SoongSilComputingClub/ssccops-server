package org.sscc.ssccopsserver.domain.member.dto;

import org.sscc.ssccopsserver.domain.member.code.RolePositionCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleAssignmentEntity;

/*
 * 회원이 현재 맡고 있는 조직 역할(회장·부회장·국장 등) 한 건.
 *
 * 종료일이 지난 역할은 담지 않는다 — '현재 역할'만 화면에 쓰이기 때문이다.
 * representative는 여러 현재 역할 중 사이드바 프로필에 대표로 표시할 하나를 가린다.
 *
 * 이 목록은 **표시용**이다. 인가 판정은 역할이 아니라 그 역할에 부여된 권한으로 하며(#9),
 * 화면이 버튼을 감출 때 쓰는 값은 여기가 아니라 MemberProfileResponse.capabilities다.
 *
 * 예외가 하나 있고 그것이 rolePstnCd다 (#118). 승인·투표 자격(#47)은 권한 코드로 표현되지
 * 않아 — 건마다 승인자가 다르다 — 역할 자체를 봐야 하는데, 그 판정이 보는 값은 화면에 쓰는
 * roleNm이 아니라 이 직위 코드다. 개명해도 자격이 흔들리지 않게 하려는 것이 요점이므로
 * **판정과 표시를 같은 DTO에 두되 서로 다른 필드로** 둔다. 지정되지 않은 역할은 null이다.
 */
public record MemberRoleResponse(
        Long roleId, String roleName, RolePositionCode rolePstnCd, boolean representative) {

    public static MemberRoleResponse from(MemberRoleAssignmentEntity assignment) {
        return new MemberRoleResponse(
                assignment.getRole().getId(),
                assignment.getRole().getName(),
                assignment.getRole().getPositionCode(),
                Boolean.TRUE.equals(assignment.getRepresentative()));
    }
}
