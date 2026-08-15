package org.sscc.ssccopsserver.domain.member.dto;

import java.time.OffsetDateTime;
import java.util.List;

import org.sscc.ssccopsserver.domain.member.entity.AuthorityEntity;

/*
 * 권한 트리의 노드 하나 (#65 GET /v1/authorities).
 *
 * 평평한 목록이 아니라 children 중첩으로 내리는 것은 화면이 체크박스 트리를 그리기 때문이다
 * (ssccops#70 수용 기준: "부여된 권한과 부여하지 않은 권한이 트리 구조로 구분되어 보인다").
 * upAuthrtCd를 함께 싣는 것은 화면이 노드를 옮길 때 현재 상위를 그대로 되돌려 보내야 하고,
 * 중첩 위치에서 부모 코드를 다시 계산하지 않아도 되게 하기 위해서다.
 *
 * children은 잎 노드에서도 null이 아니라 빈 배열이다 — 화면이 재귀 렌더링에서 null 검사를
 * 한 번 더 하지 않아도 된다.
 *
 * 조립은 서비스가 질의 한 번(권한 전량)으로 받은 목록을 메모리에서 엮는다. 권한은 수십 건
 * 규모라 재귀 CTE를 쓸 이유가 없고, 판정(AuthorityPolicy)이 같은 방식으로 펼치므로 트리를
 * 만드는 방법이 두 벌이 되지 않는다.
 */
public record AuthorityTreeResponse(
        String authrtCd,
        String authrtNm,
        String upAuthrtCd,
        String authrtExpln,
        boolean sysYn,
        Short indctSeqno,
        OffsetDateTime crtDt,
        OffsetDateTime mdfcnDt,
        List<AuthorityTreeResponse> children) {

    public static AuthorityTreeResponse of(
            AuthorityEntity authority, List<AuthorityTreeResponse> children) {

        return new AuthorityTreeResponse(
                authority.getCode(),
                authority.getName(),
                authority.getParent() == null ? null : authority.getParent().getCode(),
                authority.getExplanation(),
                authority.isSystemDefined(),
                authority.getDisplayOrder(),
                AuthorityResponse.toOffsetDateTime(authority.getCreatedAt()),
                AuthorityResponse.toOffsetDateTime(authority.getUpdatedAt()),
                children);
    }
}
