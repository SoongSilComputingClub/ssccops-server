package org.sscc.ssccopsserver.domain.content.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.content.code.ContentPublishStatus;
import org.sscc.ssccopsserver.domain.content.entity.ContentPageEntity;

/*
 * 어드민용 페이지 상세 (ssccops#381). 익명용(PublicContentPageResponse)과 **다른 record**다 —
 * 수정자·운영 타임스탬프가 여기에는 있고 그쪽에는 없다. 한 record를 쓰면 어드민 필드가 늘
 * 때마다 익명 응답으로 새어 나갈 것이 함께 는다(ADR-0038).
 *
 * 수정자 이름(mdfcnMbrNm)을 싣는 것은 MCP 출력에 회원명을 남기기로 한 ADR-0037과 같은 자리다.
 */
public record ContentPageResponse(
        Long pageId,
        String slug,
        String ttl,
        String mtxt,
        ContentPublishStatus pubSttsCd,
        OffsetDateTime pubDt,
        Long mdfcnMbrId,
        String mdfcnMbrNm,
        OffsetDateTime regDt,
        OffsetDateTime mdfcnDt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static ContentPageResponse of(ContentPageEntity page) {
        return new ContentPageResponse(
                page.getId(),
                page.getSlug(),
                page.getTitle(),
                page.getBody(),
                page.getPublishStatus(),
                toOffsetDateTime(page.getPublishedAt()),
                page.getModifier().getId(),
                page.getModifier().getName(),
                toOffsetDateTime(page.getCreatedAt()),
                toOffsetDateTime(page.getUpdatedAt()));
    }

    static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
