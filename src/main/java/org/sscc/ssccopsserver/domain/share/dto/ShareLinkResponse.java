package org.sscc.ssccopsserver.domain.share.dto;

import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.share.entity.ShareLinkEntity;

/*
 * 운영자용 공유 링크 응답 (ssccops#200).
 *
 * **URL이 아니라 토큰을 준다.** 링크가 가리키는 곳은 API가 아니라 운영 웹이므로 서버가 주소를
 * 조립하려면 웹의 호스트를 설정으로 들고 있어야 하는데, 이 저장소는 그 종류의 설정값에서 두 번
 * 데었다 — `R2_PUBLIC_BASE_URL`은 잘못된 값이 들어간 채 몇 달을 돌았고(#208), `APP_PUBLIC_BASE_URL`은
 * 비어 있는 채 배포돼 발급이 500이 됐다(#216). 웹은 자기 주소를 언제나 정확히 알고 있으므로
 * `{자기 origin}/s/{token}`을 스스로 만든다. 설정이 하나 늘지 않는 것이 그 자체로 이득이다.
 */
public record ShareLinkResponse(String shrTkn, OffsetDateTime crtDt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static ShareLinkResponse of(ShareLinkEntity link) {
        return new ShareLinkResponse(
                link.getToken(),
                link.getCreatedAt() == null
                        ? null
                        : link.getCreatedAt().atZone(SERVICE_ZONE).toOffsetDateTime());
    }
}
