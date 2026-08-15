package org.sscc.ssccopsserver.domain.member.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.member.entity.AuthorityEntity;

/*
 * 권한 한 건 (#65 생성·수정 응답).
 *
 * 트리 응답(AuthorityTreeResponse)과 필드가 같고 children만 없다. 한 DTO에 children을 두고
 * 단건에서는 null로 내리는 방법도 있지만, 그러면 "children이 null인 것"이 잎 노드인지 단건
 * 응답인지 구별되지 않아 화면이 분기를 하나 더 갖게 된다.
 *
 * 필드명은 컬럼명(authrtCd·authrtNm·upAuthrtCd·sysYn·indctSeqno)을 그대로 따른다 — 웹의 권한
 * 관리 화면(ssccops-web#32)이 이 이름으로 소비한다.
 *
 * 일시는 AP-12에 따라 Asia/Seoul 오프셋을 포함해 내려준다.
 */
public record AuthorityResponse(
        String authrtCd,
        String authrtNm,
        String upAuthrtCd,
        String authrtExpln,
        boolean sysYn,
        Short indctSeqno,
        OffsetDateTime crtDt,
        OffsetDateTime mdfcnDt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static AuthorityResponse from(AuthorityEntity authority) {
        return new AuthorityResponse(
                authority.getCode(),
                authority.getName(),
                authority.getParent() == null ? null : authority.getParent().getCode(),
                authority.getExplanation(),
                authority.isSystemDefined(),
                authority.getDisplayOrder(),
                toOffsetDateTime(authority.getCreatedAt()),
                toOffsetDateTime(authority.getUpdatedAt()));
    }

    static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
