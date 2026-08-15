package org.sscc.ssccopsserver.domain.member.dto;

import java.time.OffsetDateTime;
import java.util.List;

import org.sscc.ssccopsserver.domain.member.entity.RoleAuthorityRelationEntity;

/*
 * 역할에 부여된 권한 (#65 GET·PUT /v1/roles/{roleId}/authorities).
 *
 * **직접 부여(grants)와 펼친 결과(effectiveAuthrtCds)를 함께 내린다.** 체크박스 트리에서
 * 실제로 체크된 노드는 직접 부여된 것이고, 그 자손은 "부여된 것으로 표시되지만 체크는 아닌"
 * 상태여야 하기 때문이다 (ssccops#70 수용 기준: "상위 권한을 부여하면 자손 권한이 함께 부여된
 * 것으로 표시된다"). 화면이 직접 부여만 받아서 자손을 스스로 펼치면 그 계산이 서버의
 * AuthorityPolicy와 갈릴 수 있고, 갈리는 순간 체크 상태와 실제 인가가 어긋난다.
 *
 * 펼침은 AuthorityPolicy의 것을 그대로 쓴다 — 규칙을 여기서 다시 구현하지 않는다.
 *
 * grants의 crtDt는 "언제 이 권한이 이 역할에 붙었는가"다. 전체 교체가 차집합만 움직이므로
 * 유지되는 부여의 값은 저장 때마다 갱신되지 않는다.
 */
public record RoleAuthorityResponse(
        Long roleId,
        String roleNm,
        List<RoleAuthorityGrant> grants,
        List<String> effectiveAuthrtCds) {

    /** 역할에 직접 부여된 권한 한 건 */
    public record RoleAuthorityGrant(String authrtCd, String authrtNm, OffsetDateTime crtDt) {

        public static RoleAuthorityGrant from(RoleAuthorityRelationEntity relation) {
            return new RoleAuthorityGrant(
                    relation.getAuthority().getCode(),
                    relation.getAuthority().getName(),
                    AuthorityResponse.toOffsetDateTime(relation.getCreatedAt()));
        }
    }
}
