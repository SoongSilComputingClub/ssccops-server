package org.sscc.ssccopsserver.domain.member.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.sscc.ssccopsserver.domain.member.code.RolePositionCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleAssignmentEntity;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleEntity;

/*
 * 역할 단건 (#79 GET /v1/roles/{roleId}).
 *
 * 목록 응답(RoleResponse)과 같은 필드에 **재임 회원 목록(members)**이 더해진 모양이다. 목록에
 * 회원까지 실으면 역할 수 × 재임자 수만큼 행이 실려 화면이 쓰지도 않는 개인정보가 통째로
 * 나가므로 상세에서만 준다.
 *
 * **members의 기준은 목록의 memberCount와 같다** — 오늘이 배정 기간 안에 드는 배정만이다
 * (종료일 NULL이면 무기한). 기준을 하나로 두어야 "목록에는 3명인데 상세를 열면 2명"이 되지
 * 않는다. 지난 재임 이력을 보여주는 화면이 생긴다면 이 목록을 넓히지 말고 별도 경로를 여는
 * 것이 맞다 — 여기를 넓히는 순간 목록의 숫자와 갈린다.
 *
 * members는 배정 행 단위이고 memberCount는 사람 단위다. 한 회원에게 같은 역할이 기간이 겹치게
 * 두 번 배정된 데이터에서만 둘이 갈리는데, 그런 행을 응답에서 접어 감추지 않는 것은 화면에서
 * 보여야 고칠 수 있기 때문이다.
 */
public record RoleDetailResponse(
        Long roleId,
        Integer indctSeqno,
        String roleNm,
        String roleClsfCd,
        String roleClsfNm,
        RolePositionCode rolePstnCd,
        long memberCount,
        List<RoleMember> members,
        OffsetDateTime crtDt,
        OffsetDateTime mdfcnDt) {

    public static RoleDetailResponse of(
            MemberRoleEntity role, List<MemberRoleAssignmentEntity> currentAssignments) {

        return new RoleDetailResponse(
                role.getId(),
                role.getDisplayOrder(),
                role.getName(),
                role.getRoleClassification().getCode(),
                role.getRoleClassification().getName(),
                role.getPositionCode(),
                currentAssignments.stream()
                        .map(assignment -> assignment.getMember().getId())
                        .distinct()
                        .count(),
                currentAssignments.stream().map(RoleMember::from).toList(),
                RoleResponse.toOffsetDateTime(role.getCreatedAt()),
                RoleResponse.toOffsetDateTime(role.getUpdatedAt()));
    }

    /*
     * 재임 회원 한 명. 회원 관리 화면이 아니라 역할 상세에 딸린 목록이라 연락처·이메일은 싣지
     * 않는다 — 누가 맡고 있는지를 알아보는 데 필요한 것은 이름과 학번, 그리고 언제부터인지다.
     *
     * rprsRoleYn(대표 역할)은 그 회원이 이 역할을 프로필에 대표로 내거는가라는 표시용 값이다.
     * 인가 판정은 이 값을 보지 않는다 (BR-M26).
     */
    public record RoleMember(
            Long mbrId,
            String mbrNm,
            String stdntNo,
            LocalDate roleBgngYmd,
            LocalDate roleEndYmd,
            boolean rprsRoleYn) {

        public static RoleMember from(MemberRoleAssignmentEntity assignment) {
            return new RoleMember(
                    assignment.getMember().getId(),
                    assignment.getMember().getName(),
                    assignment.getMember().getStudentNumber(),
                    assignment.getRoleStartDate(),
                    assignment.getRoleEndDate(),
                    Boolean.TRUE.equals(assignment.getRepresentative()));
        }
    }
}
