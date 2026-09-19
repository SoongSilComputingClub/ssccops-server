package org.sscc.ssccopsserver.domain.content.controller;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.content.dto.ContentImageUploadRequest;
import org.sscc.ssccopsserver.domain.content.dto.ContentImageUploadResponse;
import org.sscc.ssccopsserver.domain.content.dto.ContentPostHistoryResponse;
import org.sscc.ssccopsserver.domain.content.dto.ContentPostListCondition;
import org.sscc.ssccopsserver.domain.content.dto.ContentPostResponse;
import org.sscc.ssccopsserver.domain.content.dto.ContentPostSaveRequest;
import org.sscc.ssccopsserver.domain.content.dto.ContentPostSearchResponse;
import org.sscc.ssccopsserver.domain.content.dto.ContentPostSummaryResponse;
import org.sscc.ssccopsserver.domain.content.service.ContentPostImageService;
import org.sscc.ssccopsserver.domain.content.service.ContentPostService;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.authorization.RequireAuthority;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 포스트 관리 API (ssccops#381 · ADR-0038). 규칙은 ContentPageController와 같다(클래스 레벨
 * CONTENT_MANAGE · PATCH는 통째로 교체 · 게시/게시 취소는 행위 경로). 여기에만 있는 것은
 * from-event(행사 갈무리 초안)와 갤러리(발급·삭제)다.
 *
 * `/from-event/{eventId}`가 `/{postId}`와 겹치지 않는 것은 리터럴 세그먼트가 경로 변수보다
 * 먼저 매칭되기 때문이다(행사의 /deleted와 같은 자리).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/content/posts")
@RequireAuthority(AuthorityCode.CONTENT_MANAGE)
public class ContentPostController {

    private final ContentPostService contentPostService;
    private final ContentPostImageService contentPostImageService;

    @Operation(
            summary = "포스트 목록 조회",
            description =
                    "어드민 목록. pubSttsCd·cntntClsfCd는 선택 필터(둘 다 주면 AND), size 기본 20·최대 100,"
                            + " cursor는 직전 응답의 page.nextCursor다(id 내림차순). 본문·갤러리는 싣지 않는다.")
    @GetMapping
    public ApiResponse<List<ContentPostSummaryResponse>> getPosts(
            @Valid @ModelAttribute ContentPostListCondition condition) {
        ContentPostSearchResponse result =
                contentPostService.getPosts(
                        condition.pubSttsCd(),
                        condition.cntntClsfCd(),
                        condition.sizeOrDefault(),
                        condition.cursor());
        return ApiResponse.success(result.posts(), result.page());
    }

    @Operation(
            summary = "포스트 단건 조회",
            description = "초안도 200이다(어드민). 갤러리(gallery)는 발급 순서다. 없으면 404 POST_NOT_FOUND.")
    @GetMapping("/{postId}")
    public ApiResponse<ContentPostResponse> getPost(@PathVariable Long postId) {
        return ApiResponse.success(contentPostService.getPost(postId));
    }

    @Operation(
            summary = "포스트 생성",
            description =
                    "상태는 언제나 DRAFT. slug 규칙·409·413은 페이지와 같다. coverFileId는 갤러리가 생긴 뒤"
                            + "(수정에서) 고른다 — 생성 본문에 값이 오면 400 COVER_NOT_IN_GALLERY."
                            + " eventId는 선택이며 실재를 검증하지 않는다.")
    @PostMapping
    public ResponseEntity<ApiResponse<ContentPostResponse>> createPost(
            @Valid @RequestBody ContentPostSaveRequest request,
            @CurrentMember MemberEntity modifier) {
        ContentPostResponse response = contentPostService.createPost(request, modifier);
        return ResponseEntity.created(URI.create("/v1/content/posts/" + response.postId()))
                .body(ApiResponse.created(response));
    }

    @Operation(
            summary = "행사에서 포스트 초안 만들기",
            description =
                    "행사의 제목·일시·장소·본문을 복사한 DRAFT 포스트를 만든다(분류 EVENT · 활동일 = 행사"
                            + " 시작일 · eventId 연결 · slug event-{eventId}, 겹치면 -2, -3…). 복사이지 연결이"
                            + " 아니다 — 그 뒤 행사가 바뀌어도 따라가지 않는다. 지운 행사·없는 행사는"
                            + " 404 EVENT_NOT_FOUND.")
    @PostMapping("/from-event/{eventId}")
    public ResponseEntity<ApiResponse<ContentPostResponse>> createPostFromEvent(
            @PathVariable Long eventId, @CurrentMember MemberEntity modifier) {
        ContentPostResponse response = contentPostService.createPostFromEvent(eventId, modifier);
        return ResponseEntity.created(URI.create("/v1/content/posts/" + response.postId()))
                .body(ApiResponse.created(response));
    }

    @Operation(
            summary = "포스트 수정",
            description =
                    "**통째로 교체**다. coverFileId는 이 포스트의 갤러리에 있는 파일이어야 한다"
                            + "(400 COVER_NOT_IN_GALLERY). 게시 상태는 바꿀 수 없다. 개정 이력이 한 행 는다.")
    @PatchMapping("/{postId}")
    public ApiResponse<ContentPostResponse> updatePost(
            @PathVariable Long postId,
            @Valid @RequestBody ContentPostSaveRequest request,
            @CurrentMember MemberEntity modifier) {
        return ApiResponse.success(contentPostService.updatePost(postId, request, modifier));
    }

    @Operation(
            summary = "포스트 게시",
            description = "DRAFT → PUBLISHED. 이미 게시면 409 CONTENT_ALREADY_PUBLISHED.")
    @PostMapping("/{postId}/publish")
    public ApiResponse<ContentPostResponse> publishPost(
            @PathVariable Long postId, @CurrentMember MemberEntity modifier) {
        return ApiResponse.success(contentPostService.publishPost(postId, modifier));
    }

    @Operation(
            summary = "포스트 게시 취소",
            description = "PUBLISHED → DRAFT. 초안이면 409 CONTENT_NOT_PUBLISHED.")
    @PostMapping("/{postId}/unpublish")
    public ApiResponse<ContentPostResponse> unpublishPost(
            @PathVariable Long postId, @CurrentMember MemberEntity modifier) {
        return ApiResponse.success(contentPostService.unpublishPost(postId, modifier));
    }

    @Operation(summary = "포스트 개정 이력", description = "최신 개정이 먼저. 표지·갤러리는 이력에 없다.")
    @GetMapping("/{postId}/history")
    public ApiResponse<List<ContentPostHistoryResponse>> getPostHistory(@PathVariable Long postId) {
        return ApiResponse.success(contentPostService.getPostHistory(postId));
    }

    /*
     * 갤러리 업로드 발급. 행사 본문 이미지(#161)와 같은 계약(presigned PUT · 서버는 바이트를 다루지
     * 않는다 · contentType은 응답 값 그대로 PUT 헤더에)이며, 다른 것은 file_rfrnc 행이 생겨
     * fileId가 응답에 실린다는 점이다. 새 자원이 생기므로 201이다.
     */
    @Operation(
            summary = "갤러리 이미지 업로드 발급",
            description =
                    "R2 presigned PUT URL(10분)과 갤러리 파일 id를 발급한다. 웹·스크립트는 uploadUrl로"
                            + " 파일 바이트를 PUT 하되 Content-Type 헤더는 응답의 contentType 값 그대로 쓴다."
                            + " imageUrl은 만료되지 않는 영구 읽기 주소(게시된 포스트에서만 열린다)."
                            + " 허용 형식은 png·jpg·webp·gif(400 UNSUPPORTED_IMAGE_TYPE), 크기 상한 10MB"
                            + "(413 IMAGE_TOO_LARGE). **발급 순간 갤러리에 한 장이 생긴다** — 올리지"
                            + " 않았으면 DELETE로 정리한다.")
    @PostMapping("/{postId}/images")
    public ResponseEntity<ApiResponse<ContentImageUploadResponse>> issueImageUploadUrl(
            @PathVariable Long postId, @Valid @RequestBody ContentImageUploadRequest request) {
        ContentImageUploadResponse response =
                contentPostImageService.issueUploadUrl(postId, request);
        return ResponseEntity.created(URI.create(response.imageUrl()))
                .body(ApiResponse.created(response));
    }

    @Operation(
            summary = "갤러리 이미지 삭제",
            description =
                    "갤러리에서 한 장을 지운다. 그 장이 표지였으면 표지도 비운다. 오브젝트는 커밋 뒤에"
                            + " 지운다. 없는 파일·다른 포스트의 파일은 404 CONTENT_IMAGE_NOT_FOUND.")
    @DeleteMapping("/{postId}/images/{fileId}")
    public ApiResponse<Void> deleteImage(@PathVariable Long postId, @PathVariable Long fileId) {
        contentPostImageService.deleteImage(postId, fileId);
        return ApiResponse.successWithNoData();
    }
}
