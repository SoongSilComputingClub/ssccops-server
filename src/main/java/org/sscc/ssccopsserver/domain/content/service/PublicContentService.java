package org.sscc.ssccopsserver.domain.content.service;

import java.util.List;

import org.sscc.ssccopsserver.domain.content.code.ContentCategory;
import org.sscc.ssccopsserver.domain.content.dto.PublicContentPageResponse;
import org.sscc.ssccopsserver.domain.content.dto.PublicContentPageSummaryResponse;
import org.sscc.ssccopsserver.domain.content.dto.PublicContentPostDetailResponse;
import org.sscc.ssccopsserver.domain.content.dto.PublicContentPostSearchResponse;

/*
 * 익명 조회 (ssccops#381 · ADR-0038). **게시본만** — 초안과 없는 것은 같은 404다. 응답 record는
 * 어드민 것과 다른 Public* 타입이며 그 타입이 «실릴 수 있는 필드»의 상한이다.
 */
public interface PublicContentService {

    PublicContentPageResponse getPublishedPage(String slug);

    /** 접두사로 시작하는 슬러그의 게시된 페이지 — slug 오름차순 (#513 · ssccops#425) */
    List<PublicContentPageSummaryResponse> getPublishedPagesBySlugPrefix(String slugPrefix);

    PublicContentPostSearchResponse getPublishedPosts(
            ContentCategory category, int size, String cursor);

    PublicContentPostDetailResponse getPublishedPost(String slug);

    /** 갤러리 한 장의 서명 URL. 게시본이 아니면 404 — 상세와 같은 판정이다 */
    String viewUrlOf(Long postId, Long fileId);

    long viewRedirectCacheMaxAgeSeconds();
}
