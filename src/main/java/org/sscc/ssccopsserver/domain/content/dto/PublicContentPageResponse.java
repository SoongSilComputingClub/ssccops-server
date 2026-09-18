package org.sscc.ssccopsserver.domain.content.dto;

import java.time.OffsetDateTime;

import org.sscc.ssccopsserver.domain.content.entity.ContentPageEntity;

/*
 * 익명용 페이지 응답 (ssccops#381 · ADR-0038). 실리는 것은 **slug·제목·본문·게시일** 넷뿐이다 —
 * 수정자·개정 이력·운영 타임스탬프·게시 상태(언제나 PUBLISHED라 정보가 아니다)는 없다.
 * 어드민 record(ContentPageResponse)와 다른 타입으로 둔 것이 곧 «실릴 수 있는 필드»의 상한이고,
 * PublicContentDtoContractTest가 이 record의 컴포넌트를 금지 목록과 대조한다.
 */
public record PublicContentPageResponse(
        String slug, String ttl, String mtxt, OffsetDateTime pubDt) {

    public static PublicContentPageResponse of(ContentPageEntity page) {
        return new PublicContentPageResponse(
                page.getSlug(),
                page.getTitle(),
                page.getBody(),
                ContentPageResponse.toOffsetDateTime(page.getPublishedAt()));
    }
}
