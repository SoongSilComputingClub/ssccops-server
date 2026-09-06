package org.sscc.ssccopsserver.domain.operation.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.operation.entity.OperationShareLinkEntity;

/*
 * 공유 링크 발급·조회 응답 (ssccops#200 · ADR-0016).
 *
 * **완성된 URL이 아니라 토큰을 내린다.** 링크의 호스트·경로(/s/{token})는 웹의 것이고
 * 서버는 그 주소를 모른다 — 행사 이미지가 우리 API의 주소(app.public-base-url)를 조립해
 * 내리는 것과 갈리는 지점이다. 그쪽은 우리가 서빙하는 자원이라 우리 주소가 맞지만,
 * 공유 페이지는 웹 앱의 화면이라 서버가 그 주소를 안다고 가정하면 배포마다 갈린다.
 *
 * **만료 필드가 없다.** 만료를 두지 않기로 했으므로(ADR-0016) 내릴 값이 없다 —
 * 늘 null인 필드를 두면 "언젠가 만료되는 경우도 있나" 하는 의문을 만든다.
 */
public record OperationShareLinkResponse(
        Long operShrLnkId, Long operId, String shrTkn, OffsetDateTime crtDt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static OperationShareLinkResponse from(OperationShareLinkEntity link) {
        return new OperationShareLinkResponse(
                link.getId(),
                link.getOperation().getId(),
                link.getToken(),
                toOffsetDateTime(link.getCreatedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
