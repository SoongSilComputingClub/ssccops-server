package org.sscc.ssccopsserver.domain.content.dto;

import java.time.OffsetDateTime;

import org.sscc.ssccopsserver.domain.content.entity.ContentPageEntity;

/**
 * 익명 게시 페이지 목록의 한 줄 (#513 · ssccops#425) — GET /public/v1/pages?slugPrefix=…
 *
 * <p>본문(mtxt)이 없다. 이 목록의 용도는 «어떤 슬러그가 게시돼 있나»(역대 운영진의 대수 탭)라 제목과 게시일이면 되고, 본문까지 실으면 접두사 하나로 페이지 열
 * 장의 마크다운이 한 응답에 실린다. 공개 필드 규칙은 {@link PublicContentPageResponse}와 같고 {@code
 * PublicContentDtoContractTest}가 대조한다.
 */
public record PublicContentPageSummaryResponse(String slug, String ttl, OffsetDateTime pubDt) {

    public static PublicContentPageSummaryResponse of(ContentPageEntity page) {
        return new PublicContentPageSummaryResponse(
                page.getSlug(),
                page.getTitle(),
                ContentPageResponse.toOffsetDateTime(page.getPublishedAt()));
    }
}
