package org.sscc.ssccopsserver.domain.content.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.sscc.ssccopsserver.domain.content.code.ContentCategory;
import org.sscc.ssccopsserver.domain.content.entity.ContentPostEntity;

/** 익명 목록 항목 — 카드 한 장에 필요한 것만. 본문·갤러리는 상세에서 */
public record PublicContentPostSummaryResponse(
        String slug,
        ContentCategory cntntClsfCd,
        String ttl,
        String smry,
        LocalDate actvYmd,
        Long coverFileId,
        String coverImageUrl,
        OffsetDateTime pubDt) {

    public static PublicContentPostSummaryResponse of(
            ContentPostEntity post, String coverImageUrl) {
        return new PublicContentPostSummaryResponse(
                post.getSlug(),
                post.getCategory(),
                post.getTitle(),
                post.getSummary(),
                post.getActivityDate(),
                post.getCoverFileId(),
                coverImageUrl,
                ContentPageResponse.toOffsetDateTime(post.getPublishedAt()));
    }
}
