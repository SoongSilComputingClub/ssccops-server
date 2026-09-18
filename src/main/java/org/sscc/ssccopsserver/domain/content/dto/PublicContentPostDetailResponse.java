package org.sscc.ssccopsserver.domain.content.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.sscc.ssccopsserver.domain.content.code.ContentCategory;
import org.sscc.ssccopsserver.domain.content.entity.ContentPostEntity;

/*
 * 익명용 포스트 상세 (ADR-0038). slug·제목·요약·본문·분류·활동일·표지·갤러리·eventId·게시일 —
 * ADR의 표 그대로이며 작성자·수정자·이력은 없다. eventId는 www가 /public/v1/events/{id}로
 * 이어 붙일 링크 재료다(지운 행사면 그쪽이 404다).
 */
public record PublicContentPostDetailResponse(
        String slug,
        ContentCategory cntntClsfCd,
        String ttl,
        String smry,
        String mtxt,
        LocalDate actvYmd,
        Long eventId,
        Long coverFileId,
        String coverImageUrl,
        List<ContentImageResponse> gallery,
        OffsetDateTime pubDt) {

    public static PublicContentPostDetailResponse of(
            ContentPostEntity post, String coverImageUrl, List<ContentImageResponse> gallery) {
        return new PublicContentPostDetailResponse(
                post.getSlug(),
                post.getCategory(),
                post.getTitle(),
                post.getSummary(),
                post.getBody(),
                post.getActivityDate(),
                post.getEventId(),
                post.getCoverFileId(),
                coverImageUrl,
                gallery,
                ContentPageResponse.toOffsetDateTime(post.getPublishedAt()));
    }
}
