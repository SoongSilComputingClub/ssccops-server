package org.sscc.ssccopsserver.domain.content.controller;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.content.dto.PublicContentPageResponse;
import org.sscc.ssccopsserver.domain.content.dto.PublicContentPostDetailResponse;
import org.sscc.ssccopsserver.domain.content.dto.PublicContentPostListCondition;
import org.sscc.ssccopsserver.domain.content.dto.PublicContentPostSearchResponse;
import org.sscc.ssccopsserver.domain.content.dto.PublicContentPostSummaryResponse;
import org.sscc.ssccopsserver.domain.content.service.PublicContentService;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.apipayload.PublicCacheControl;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 익명 콘텐츠 조회 (ssccops#381 · ADR-0038). `/public/v1/**`는 SecurityConfig가 permitAll 하는
 * 유일한 업무 API 접두사다 — **여기에 핸들러를 더하는 것은 permitAll을 더하는 것과 같다.**
 * 이 컨트롤러가 내는 것은 «게시 상태인 것의 공개용 필드»뿐이며(Public* record), 초안·수정자·
 * 이력은 어느 응답에도 없다. 응답 record의 컴포넌트는 PublicContentDtoContractTest가 금지 목록과
 * 대조한다.
 *
 * 응답마다 `Cache-Control: public, s-maxage=300, stale-while-revalidate=600`을 싣는다
 * (PublicCacheControl) — www 앞 CDN이 5분 캐싱하며 게시 취소가 그만큼 늦는 것은 ADR-0038이
 * 감수한 대가다. 404에는 캐시를 붙이지 않는다(GlobalExceptionHandler를 지나므로 헤더가 없다) —
 * 게시 직후의 «아직 없음»이 CDN에 5분 굳으면 게시가 그만큼 늦어 보인다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/public/v1")
public class PublicContentController {

    private final PublicContentService publicContentService;

    @Operation(
            summary = "공개 페이지 조회(익명)",
            description =
                    "게시(PUBLISHED)된 페이지의 slug·제목·본문(마크다운 원문)·게시일. **인증이 필요 없다.**"
                            + " 초안과 없는 slug는 둘 다 404 PAGE_NOT_FOUND — 초안의 존재를 드러내지 않는다."
                            + " 본문 렌더링·raw HTML 차단은 www 렌더러의 몫이다.")
    @GetMapping("/pages/{slug}")
    public ResponseEntity<ApiResponse<PublicContentPageResponse>> getPage(
            @PathVariable String slug) {
        return cached(ApiResponse.success(publicContentService.getPublishedPage(slug)));
    }

    @Operation(
            summary = "공개 포스트 목록(익명)",
            description =
                    "게시된 포스트를 활동일(actvYmd) 최신순으로. category(ACADEMIC·EVENT·NEWS)는 선택 필터,"
                            + " size 기본 20·최대 100, cursor는 직전 응답의 page.nextCursor. 항목에는 본문·"
                            + "갤러리가 없고 표지 주소(coverImageUrl)만 있다. page.totalCount는 필터 적용 건수,"
                            + " overallCount는 게시본 전체 건수다.")
    @GetMapping("/posts")
    public ResponseEntity<ApiResponse<List<PublicContentPostSummaryResponse>>> getPosts(
            @Valid @ModelAttribute PublicContentPostListCondition condition) {
        PublicContentPostSearchResponse result =
                publicContentService.getPublishedPosts(
                        condition.category(), condition.sizeOrDefault(), condition.cursor());
        return cached(ApiResponse.success(result.posts(), result.page()));
    }

    @Operation(
            summary = "공개 포스트 상세(익명)",
            description =
                    "게시된 포스트의 slug·분류·제목·요약·본문·활동일·eventId·표지·갤러리·게시일."
                            + " 초안과 없는 slug는 둘 다 404 POST_NOT_FOUND. 갤러리 항목의 imageUrl은"
                            + " 302 리다이렉트 주소다(아래).")
    @GetMapping("/posts/{slug}")
    public ResponseEntity<ApiResponse<PublicContentPostDetailResponse>> getPost(
            @PathVariable String slug) {
        return cached(ApiResponse.success(publicContentService.getPublishedPost(slug)));
    }

    /*
     * 갤러리 이미지 읽기 — 행사 이미지(PublicEventImageController · #208)와 같은 302 구조다.
     * 경로의 postId는 slug가 아니라 숫자 id다(ContentImageLocation 주석 — slug는 바뀔 수 있고
     * 본문에 굳은 주소가 깨져서는 안 된다). /posts/{slug}와 세그먼트 수가 달라 충돌하지 않는다.
     */
    @Operation(
            summary = "포스트 갤러리 이미지 읽기(익명)",
            description =
                    "서명된 R2 GET URL로 302 리다이렉트한다. 이 주소는 만료되지 않고 열릴 때마다 15분짜리"
                            + " 서명이 새로 만들어지며, 302에는 서명보다 짧은 Cache-Control(public, max-age)이"
                            + " 실린다. 게시되지 않은 포스트·없는 포스트는 404 POST_NOT_FOUND, 갤러리에 없는"
                            + " 파일은 404 CONTENT_IMAGE_NOT_FOUND(404에는 캐시가 없다).")
    @GetMapping("/posts/{postId}/images/{fileId}")
    public ResponseEntity<Void> redirectToImage(
            @PathVariable Long postId, @PathVariable Long fileId) {
        String signedUrl = publicContentService.viewUrlOf(postId, fileId);
        Duration cacheMaxAge =
                Duration.ofSeconds(publicContentService.viewRedirectCacheMaxAgeSeconds());
        return ResponseEntity.status(HttpStatus.FOUND)
                .cacheControl(CacheControl.maxAge(cacheMaxAge).cachePublic())
                .location(URI.create(signedUrl))
                .build();
    }

    private static <T> ResponseEntity<ApiResponse<T>> cached(ApiResponse<T> body) {
        return ResponseEntity.ok().cacheControl(PublicCacheControl.anonymousContent()).body(body);
    }
}
