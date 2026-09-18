package org.sscc.ssccopsserver.domain.content.dto;

import java.time.OffsetDateTime;

import org.sscc.ssccopsserver.domain.content.code.ContentPublishStatus;
import org.sscc.ssccopsserver.domain.content.entity.ContentPageEntity;

/** 어드민 목록 항목. 본문(mtxt)은 싣지 않는다 — 10만 자 × 건수다 (행사 목록과 같은 이유) */
public record ContentPageSummaryResponse(
        Long pageId,
        String slug,
        String ttl,
        ContentPublishStatus pubSttsCd,
        OffsetDateTime pubDt,
        OffsetDateTime mdfcnDt) {

    public static ContentPageSummaryResponse of(ContentPageEntity page) {
        return new ContentPageSummaryResponse(
                page.getId(),
                page.getSlug(),
                page.getTitle(),
                page.getPublishStatus(),
                ContentPageResponse.toOffsetDateTime(page.getPublishedAt()),
                ContentPageResponse.toOffsetDateTime(page.getUpdatedAt()));
    }
}
