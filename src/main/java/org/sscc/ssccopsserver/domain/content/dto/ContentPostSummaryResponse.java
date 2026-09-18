package org.sscc.ssccopsserver.domain.content.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.sscc.ssccopsserver.domain.content.code.ContentCategory;
import org.sscc.ssccopsserver.domain.content.code.ContentPublishStatus;
import org.sscc.ssccopsserver.domain.content.entity.ContentPostEntity;

/** 어드민 목록 항목. 본문·갤러리는 싣지 않는다 */
public record ContentPostSummaryResponse(
        Long postId,
        String slug,
        ContentCategory cntntClsfCd,
        String ttl,
        LocalDate actvYmd,
        ContentPublishStatus pubSttsCd,
        OffsetDateTime pubDt,
        OffsetDateTime mdfcnDt) {

    public static ContentPostSummaryResponse of(ContentPostEntity post) {
        return new ContentPostSummaryResponse(
                post.getId(),
                post.getSlug(),
                post.getCategory(),
                post.getTitle(),
                post.getActivityDate(),
                post.getPublishStatus(),
                ContentPageResponse.toOffsetDateTime(post.getPublishedAt()),
                ContentPageResponse.toOffsetDateTime(post.getUpdatedAt()));
    }
}
