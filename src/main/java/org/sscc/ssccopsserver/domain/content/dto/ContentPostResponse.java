package org.sscc.ssccopsserver.domain.content.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.sscc.ssccopsserver.domain.content.code.ContentCategory;
import org.sscc.ssccopsserver.domain.content.code.ContentPublishStatus;
import org.sscc.ssccopsserver.domain.content.entity.ContentPostEntity;

/** 어드민용 포스트 상세. 익명용(PublicContentPostDetailResponse)과 다른 record다 (ADR-0038) */
public record ContentPostResponse(
        Long postId,
        String slug,
        ContentCategory cntntClsfCd,
        String ttl,
        String smry,
        String mtxt,
        LocalDate actvYmd,
        Long eventId,
        Long coverFileId,
        List<ContentImageResponse> gallery,
        ContentPublishStatus pubSttsCd,
        OffsetDateTime pubDt,
        Long mdfcnMbrId,
        String mdfcnMbrNm,
        OffsetDateTime regDt,
        OffsetDateTime mdfcnDt) {

    public static ContentPostResponse of(
            ContentPostEntity post, List<ContentImageResponse> gallery) {
        return new ContentPostResponse(
                post.getId(),
                post.getSlug(),
                post.getCategory(),
                post.getTitle(),
                post.getSummary(),
                post.getBody(),
                post.getActivityDate(),
                post.getEventId(),
                post.getCoverFileId(),
                gallery,
                post.getPublishStatus(),
                ContentPageResponse.toOffsetDateTime(post.getPublishedAt()),
                post.getModifier().getId(),
                post.getModifier().getName(),
                ContentPageResponse.toOffsetDateTime(post.getCreatedAt()),
                ContentPageResponse.toOffsetDateTime(post.getUpdatedAt()));
    }
}
