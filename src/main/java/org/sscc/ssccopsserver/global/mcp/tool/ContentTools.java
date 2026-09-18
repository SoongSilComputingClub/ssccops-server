package org.sscc.ssccopsserver.global.mcp.tool;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import org.sscc.ssccopsserver.domain.content.dto.ContentImageUploadRequest;
import org.sscc.ssccopsserver.domain.content.dto.ContentImageUploadResponse;
import org.sscc.ssccopsserver.domain.content.dto.ContentPageResponse;
import org.sscc.ssccopsserver.domain.content.dto.ContentPageSaveRequest;
import org.sscc.ssccopsserver.domain.content.dto.ContentPostResponse;
import org.sscc.ssccopsserver.domain.content.dto.ContentPostSaveRequest;
import org.sscc.ssccopsserver.global.mcp.client.McpRestClient;
import org.sscc.ssccopsserver.global.mcp.client.McpToolException;
import org.sscc.ssccopsserver.global.mcp.tool.patch.ContentPagePatch;
import org.sscc.ssccopsserver.global.mcp.tool.patch.ContentPostPatch;

import io.modelcontextprotocol.common.McpTransportContext;

import lombok.RequiredArgsConstructor;

/*
 * 콘텐츠 도구 여섯 (ssccops#381 · ADR-0038 · Story의 MCP 항목). 규칙은 WorkTools·MeetingTools와
 * 같다 — REST 자기 호출(McpRestClient) · 입력·출력은 컨트롤러 record 그대로 · 로그는 도구
 * 이름과 대상 id만. 인가(CONTENT_MANAGE)·검증·감사는 REST 층이 그대로 한다.
 *
 * publish_content 하나가 페이지·포스트를 함께 받는 것은 «게시»가 한 동작이기 때문이다 —
 * 도구가 넷(page/post × publish/unpublish)이면 모델이 고를 것이 늘 뿐이다. 그래서 출력 타입이
 * Object다(kind에 따라 ContentPageResponse 또는 ContentPostResponse) — 도구용 합집합 record를
 * 만들면 «도구용 DTO 없음» 규칙의 예외가 하나 더 생긴다.
 *
 * request_content_image_upload는 presigned PUT URL과 fileId를 돌려줄 뿐 바이트를 올리지 않는다 —
 * 올리는 것은 스크립트(curl -T · Content-Type은 응답의 contentType)의 몫이다. 서버가 바이트를
 * 받지 않는 구조(#107)는 MCP에서도 같다.
 */
@Component
@RequiredArgsConstructor
public class ContentTools {

    private static final Logger log = LoggerFactory.getLogger(ContentTools.class);

    private final McpRestClient client;

    @McpTool(
            name = "create_page",
            description =
                    "공개 사이트 페이지(소개·연혁·FAQ 같은 단건 문서)를 초안으로 만든다. slug(소문자·숫자·"
                            + "하이픈)·ttl(제목)·mtxt(마크다운 본문)가 필수다. 게시는 publish_content로."
                            + " 콘텐츠 관리(CONTENT_MANAGE) 권한이 필요하다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public ContentPageResponse createPage(
            @McpToolParam(description = "페이지 생성 요청 — slug·ttl·mtxt") ContentPageSaveRequest request,
            McpTransportContext context) {
        log.info("mcp tool create_page");
        return client.post(context, "/v1/content/pages", request, ContentPageResponse.class);
    }

    @McpTool(
            name = "update_page",
            description =
                    "페이지의 값을 바꾼다. **바꿀 필드만 준다** — 나머지는 현재 값이 유지된다(도구가"
                            + " 상세를 먼저 읽어 채운다). 게시 상태는 publish_content로. 읽은 뒤 저장하기까지"
                            + " 다른 사람이 같은 페이지를 고치면 그 변경이 되돌려질 수 있다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public ContentPageResponse updatePage(
            @McpToolParam(description = "페이지 id") Long pageId,
            @McpToolParam(description = "바꿀 필드만. 비운 필드는 현재 값을 유지한다") ContentPagePatch patch,
            McpTransportContext context) {
        log.info("mcp tool update_page pageId={}", pageId);
        ContentPageResponse current =
                client.get(context, "/v1/content/pages/" + pageId, ContentPageResponse.class);
        return client.patch(
                context,
                "/v1/content/pages/" + pageId,
                patch.merge(current),
                ContentPageResponse.class);
    }

    @McpTool(
            name = "create_post",
            description =
                    "공개 사이트 포스트(활동 아카이브)를 초안으로 만든다. slug·cntntClsfCd(ACADEMIC·EVENT·"
                            + "NEWS)·ttl·mtxt·actvYmd(활동일)가 필수, smry(요약)·eventId는 선택이다."
                            + " coverFileId는 만들 때 줄 수 없다 — request_content_image_upload로 갤러리를"
                            + " 만든 뒤 update_post로 고른다. 게시는 publish_content로.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public ContentPostResponse createPost(
            @McpToolParam(description = "포스트 생성 요청") ContentPostSaveRequest request,
            McpTransportContext context) {
        log.info("mcp tool create_post");
        return client.post(context, "/v1/content/posts", request, ContentPostResponse.class);
    }

    @McpTool(
            name = "update_post",
            description =
                    "포스트의 값을 바꾼다. **바꿀 필드만 준다** — 나머지는 현재 값이 유지된다."
                            + " coverFileId는 이 포스트의 갤러리 파일 id여야 한다. 요약·행사·표지를 비우는"
                            + " 것은 이 도구로 할 수 없다(어드민 화면에서). 덮어쓰기 경합은 update_page와 같다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public ContentPostResponse updatePost(
            @McpToolParam(description = "포스트 id") Long postId,
            @McpToolParam(description = "바꿀 필드만. 비운 필드는 현재 값을 유지한다") ContentPostPatch patch,
            McpTransportContext context) {
        log.info("mcp tool update_post postId={}", postId);
        ContentPostResponse current =
                client.get(context, "/v1/content/posts/" + postId, ContentPostResponse.class);
        return client.patch(
                context,
                "/v1/content/posts/" + postId,
                patch.merge(current),
                ContentPostResponse.class);
    }

    @McpTool(
            name = "publish_content",
            description =
                    "페이지 또는 포스트를 게시하거나 게시 취소한다. kind는 page|post, id는 그 식별자,"
                            + " publish=true면 게시(익명 사이트에 나온다 — CDN 캐시 때문에 최대 5분 뒤),"
                            + " false면 게시 취소(초안으로). 이미 그 상태면 409로 거절된다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public Object publishContent(
            @McpToolParam(description = "page 또는 post") String kind,
            @McpToolParam(description = "페이지 id 또는 포스트 id") Long id,
            @McpToolParam(description = "true=게시 · false=게시 취소") boolean publish,
            McpTransportContext context) {
        String normalized = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        String action = publish ? "publish" : "unpublish";
        log.info("mcp tool publish_content kind={} id={} action={}", normalized, id, action);
        return switch (normalized) {
            case "page" ->
                    client.post(
                            context,
                            "/v1/content/pages/" + id + "/" + action,
                            null,
                            ContentPageResponse.class);
            case "post" ->
                    client.post(
                            context,
                            "/v1/content/posts/" + id + "/" + action,
                            null,
                            ContentPostResponse.class);
            default ->
                    throw new McpToolException(
                            "INVALID_PARAMETER", "kind는 page 또는 post여야 합니다: " + kind);
        };
    }

    @McpTool(
            name = "request_content_image_upload",
            description =
                    "포스트 갤러리에 올릴 이미지의 업로드 주소를 발급한다. fileExt(png·jpg·webp·gif)와"
                            + " fileSize(바이트)를 주면 uploadUrl(10분짜리 presigned PUT)·fileId·imageUrl·"
                            + "contentType이 온다. **바이트는 이 도구가 올리지 않는다** — 스크립트가 uploadUrl로"
                            + " PUT 하되 Content-Type 헤더는 응답의 contentType 그대로 쓴다. 발급 순간 갤러리에"
                            + " 한 장이 생기므로 올리지 않을 거면 발급하지 않는다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
    public ContentImageUploadResponse requestContentImageUpload(
            @McpToolParam(description = "포스트 id") Long postId,
            @McpToolParam(description = "fileExt·fileSize") ContentImageUploadRequest request,
            McpTransportContext context) {
        log.info("mcp tool request_content_image_upload postId={}", postId);
        return client.post(
                context,
                "/v1/content/posts/" + postId + "/images",
                request,
                ContentImageUploadResponse.class);
    }
}
