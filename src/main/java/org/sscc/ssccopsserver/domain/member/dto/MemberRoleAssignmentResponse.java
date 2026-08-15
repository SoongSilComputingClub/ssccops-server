package org.sscc.ssccopsserver.domain.member.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.sscc.ssccopsserver.domain.member.entity.MemberRoleAssignmentEntity;

/*
 * 회원 역할 배정 한 건 (#81 목록·부여·수정 응답).
 *
 * 필드명은 데이터사전의 컬럼명을 그대로 따른다(mbrRoleId·roleBgngYmd·roleEndYmd·rprsRoleYn) —
 * 웹 회원 역할 화면(ssccops-web#50)이 이 이름으로 소비한다.
 *
 * **current는 저장된 값이 아니라 조회할 때마다 다시 계산하는 파생 값이다.** 기준은 BR-M25
 * (role_bgng_ymd <= 오늘 <= role_end_ymd, 종료일 NULL이면 무기한)이며 오늘은 주입된 Clock에서
 * 온다. 화면이 종료일만 보고 스스로 판단하면 "종료일이 미래로 채워진 배정"(임기가 정해진 국장)을
 * 지난 역할로 그리게 되고, 그 순간 배지와 실제 인가가 갈린다 — 폼의 receiptStatus(#33)와 같은
 * 자리다.
 *
 * 역할 분류(roleClsfCd·roleClsfNm)는 싣지 않는다. 이 목록은 '이 사람이 무엇을 맡았는가'를
 * 보여주는 자리이고 분류까지 끌어오려면 조인이 한 단계 더 깊어진다 — 필요해지면 역할 목록
 * (GET /v1/roles)이 이미 그 값을 내리고 있으므로 화면이 짝지으면 된다.
 *
 * 일시는 AP-12에 따라 Asia/Seoul 오프셋을 포함해 내려준다.
 */
public record MemberRoleAssignmentResponse(
        Long mbrRoleId,
        Long mbrId,
        Long roleId,
        String roleNm,
        LocalDate roleBgngYmd,
        LocalDate roleEndYmd,
        boolean rprsRoleYn,
        boolean current,
        OffsetDateTime crtDt,
        OffsetDateTime mdfcnDt) {

    public static MemberRoleAssignmentResponse of(
            MemberRoleAssignmentEntity assignment, LocalDate today) {

        return new MemberRoleAssignmentResponse(
                assignment.getId(),
                assignment.getMember().getId(),
                assignment.getRole().getId(),
                assignment.getRole().getName(),
                assignment.getRoleStartDate(),
                assignment.getRoleEndDate(),
                Boolean.TRUE.equals(assignment.getRepresentative()),
                isCurrent(assignment, today),
                RoleResponse.toOffsetDateTime(assignment.getCreatedAt()),
                RoleResponse.toOffsetDateTime(assignment.getUpdatedAt()));
    }

    /*
     * 유효 역할 판정 (BR-M25). 질의로 거르는 쪽은 MemberRoleAssignmentRepository가 갖고 있으므로
     * 규칙이 두 벌이 되는 것처럼 보이지만, 이쪽은 **이미 손에 든 행 하나**를 판정하는 자리다 —
     * current=false 목록에는 종료된 배정도 함께 실리므로 행마다 다시 물어볼 곳이 필요하다.
     * 두 곳이 갈리면 목록의 배지와 current=true 목록의 내용이 어긋나므로 조건을 그대로 옮겨 적는다.
     */
    private static boolean isCurrent(MemberRoleAssignmentEntity assignment, LocalDate today) {
        if (assignment.getRoleStartDate().isAfter(today)) {
            return false;
        }
        return assignment.getRoleEndDate() == null || !assignment.getRoleEndDate().isBefore(today);
    }
}
