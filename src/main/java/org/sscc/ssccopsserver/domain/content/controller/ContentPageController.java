package org.sscc.ssccopsserver.domain.content.controller;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.content.dto.ContentPageHistoryResponse;
import org.sscc.ssccopsserver.domain.content.dto.ContentPageListCondition;
import org.sscc.ssccopsserver.domain.content.dto.ContentPageResponse;
import org.sscc.ssccopsserver.domain.content.dto.ContentPageSaveRequest;
import org.sscc.ssccopsserver.domain.content.dto.ContentPageSearchResponse;
import org.sscc.ssccopsserver.domain.content.dto.ContentPageSummaryResponse;
import org.sscc.ssccopsserver.domain.content.service.ContentPageService;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.authorization.RequireAuthority;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 페이지 관리 API (ssccops#381 · ADR-0038). **클래스 레벨 @RequireAuthority(CONTENT_MANAGE)** —
 * 행사(EVENT_MANAGE)처럼 쪼갤 자식 권한이 없다. 익명 조회는 이 컨트롤러가 아니라
 * PublicContentController(/public/v1)의 몫이며 여기에는 permitAll 경로가 없다.
 *
 * 수정은 PATCH이지만 **통째로 교체**다(운영 도메인 F2와 같은 계약 — MCP update_page가 읽고-합치기로
 * 이 성질을 감싼다). 게시·게시 취소는 본문 없는 행위 경로 둘이다(AP-03 — 상태를 PATCH 필드로
 * 넘기는 길을 두지 않는다).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/content/pages")
@RequireAuthority(AuthorityCode.CONTENT_MANAGE)
public class ContentPageController {

    private final ContentPageService contentPageService;

    @Operation(
            summary = "페이지 목록 조회",
            description =
                    "어드민 목록. pubSttsCd(DRAFT·PUBLISHED)는 선택 필터, size 기본 20·최대 100, cursor는"
                            + " 직전 응답의 page.nextCursor다(id 내림차순). 본문(mtxt)은 싣지 않는다.")
    @GetMapping
    public ApiResponse<List<ContentPageSummaryResponse>> getPages(
            @Valid @ModelAttribute ContentPageListCondition condition) {
        ContentPageSearchResponse result =
                contentPageService.getPages(
                        condition.pubSttsCd(), condition.sizeOrDefault(), condition.cursor());
        return ApiResponse.success(result.pages(), result.page());
    }

    @Operation(summary = "페이지 단건 조회", description = "초안도 200이다(어드민). 없는 페이지는 404 PAGE_NOT_FOUND.")
    @GetMapping("/{pageId}")
    public ApiResponse<ContentPageResponse> getPage(@PathVariable Long pageId) {
        return ApiResponse.success(contentPageService.getPage(pageId));
    }

    @Operation(
            summary = "페이지 생성",
            description =
                    "상태는 언제나 DRAFT다 — 게시는 POST …/{pageId}/publish. slug는 소문자·숫자·하이픈"
                            + "(최대 80자)이며 겹치면 409 CONTENT_SLUG_DUPLICATED, 본문이 10만 자를 넘으면"
                            + " 413 CONTENT_TOO_LARGE. 수정자는 인증 주체에서 채운다.")
    @PostMapping
    public ResponseEntity<ApiResponse<ContentPageResponse>> createPage(
            @Valid @RequestBody ContentPageSaveRequest request,
            @CurrentMember MemberEntity modifier) {
        ContentPageResponse response = contentPageService.createPage(request, modifier);
        return ResponseEntity.created(URI.create("/v1/content/pages/" + response.pageId()))
                .body(ApiResponse.created(response));
    }

    @Operation(
            summary = "페이지 수정",
            description =
                    "**통째로 교체**다 — 본문에 없는 값은 지운 것으로 본다(부분 수정이 아니다)."
                            + " 게시 상태는 이 API로 바꿀 수 없다. 개정 이력이 한 행 는다.")
    @PatchMapping("/{pageId}")
    public ApiResponse<ContentPageResponse> updatePage(
            @PathVariable Long pageId,
            @Valid @RequestBody ContentPageSaveRequest request,
            @CurrentMember MemberEntity modifier) {
        return ApiResponse.success(contentPageService.updatePage(pageId, request, modifier));
    }

    @Operation(
            summary = "페이지 게시",
            description =
                    "DRAFT → PUBLISHED. 익명 GET /public/v1/pages/{slug}에 나오기 시작한다"
                            + "(CDN 캐시 때문에 최대 5분 뒤). 이미 게시된 페이지는 409 CONTENT_ALREADY_PUBLISHED.")
    @PostMapping("/{pageId}/publish")
    public ApiResponse<ContentPageResponse> publishPage(
            @PathVariable Long pageId, @CurrentMember MemberEntity modifier) {
        return ApiResponse.success(contentPageService.publishPage(pageId, modifier));
    }

    @Operation(
            summary = "페이지 게시 취소",
            description =
                    "PUBLISHED → DRAFT. 익명에서 사라진다(CDN 캐시 최대 5분). 초안은 409 CONTENT_NOT_PUBLISHED.")
    @PostMapping("/{pageId}/unpublish")
    public ApiResponse<ContentPageResponse> unpublishPage(
            @PathVariable Long pageId, @CurrentMember MemberEntity modifier) {
        return ApiResponse.success(contentPageService.unpublishPage(pageId, modifier));
    }

    @Operation(summary = "페이지 개정 이력", description = "최신 개정이 먼저. 한 행이 그 시점의 제목·본문·상태 전체다(스냅샷).")
    @GetMapping("/{pageId}/history")
    public ApiResponse<List<ContentPageHistoryResponse>> getPageHistory(@PathVariable Long pageId) {
        return ApiResponse.success(contentPageService.getPageHistory(pageId));
    }
}
