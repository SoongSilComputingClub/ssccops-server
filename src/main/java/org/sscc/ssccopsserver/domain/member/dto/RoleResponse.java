package org.sscc.ssccopsserver.domain.member.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.member.code.RolePositionCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberRoleEntity;

/*
 * 역할 한 건 (#79 목록·생성·수정 응답).
 *
 * 필드명은 데이터사전의 컬럼명을 그대로 따른다(roleId·indctSeqno·roleNm·roleClsfCd·crtDt·
 * mdfcnDt) — 웹 역할 관리 화면(ssccops-web#49)이 이 이름으로 소비한다. roleClsfNm은 컬럼이
 * 아니라 분류 조인의 결과지만 함께 싣는다. 화면이 '직책'이라고 써야 하는데 코드만 내리면
 * 분류 목록을 한 번 더 받아 짝지어야 한다.
 *
 * memberCount는 **지금 이 역할을 맡고 있는 회원 수**다 — role_bgng_ymd <= 오늘 <= role_end_ymd
 * (종료일 NULL이면 무기한)로, AuthorityPolicy가 유효 역할을 고르는 기준과 같다. 종료된 배정은
 * 세지 않으므로 이 값이 0이어도 삭제되지 않을 수 있다(삭제는 이력이 하나라도 있으면 막는다,
 * MemberErrorCode.ROLE_IN_USE 참고). 두 기준이 다르다는 것을 화면이 알아야 한다.
 *
 * use_yn 같은 활성 여부 필드는 없다 — 데이터사전의 role에 그 컬럼이 없고, 응답에만 만들어
 * 두면 화면이 존재하지 않는 상태를 그리게 된다.
 *
 * rolePstnCd는 승인·투표 자격을 가르는 직위 코드다 (#118). 지정되지 않은 역할은 null이며
 * 그 상태에서는 어느 쪽 자격도 없다 — 화면은 이 값으로 '승인권 있음' 표시를 그린다.
 * 분류(roleClsfCd)와 축이 다르므로 둘을 같은 칸에 그리지 말 것.
 *
 * 일시는 AP-12에 따라 Asia/Seoul 오프셋을 포함해 내려준다.
 */
public record RoleResponse(
        Long roleId,
        Integer indctSeqno,
        String roleNm,
        String roleClsfCd,
        String roleClsfNm,
        RolePositionCode rolePstnCd,
        long memberCount,
        OffsetDateTime crtDt,
        OffsetDateTime mdfcnDt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static RoleResponse of(MemberRoleEntity role, long memberCount) {
        return new RoleResponse(
                role.getId(),
                role.getDisplayOrder(),
                role.getName(),
                role.getRoleClassification().getCode(),
                role.getRoleClassification().getName(),
                role.getPositionCode(),
                memberCount,
                toOffsetDateTime(role.getCreatedAt()),
                toOffsetDateTime(role.getUpdatedAt()));
    }

    static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
