package org.sscc.ssccopsserver.domain.content.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.sscc.ssccopsserver.domain.content.code.ContentSlug;

/*
 * 페이지 생성·수정 본문 (ssccops#381). 생성(POST)과 수정(PATCH)이 같은 모양이다 — 수정은
 * 통째로 교체이므로 «바뀔 수 있는 것 전부»가 곧 «만들 때 주는 것 전부»다. 게시 상태는 없다
 * (publish/unpublish 경로가 따로 있다). 본문 길이 상한은 @Size가 아니라 서비스가 413으로
 * 본다(ContentErrorCode.CONTENT_TOO_LARGE 주석).
 *
 * MCP의 ContentPagePatch가 이 record를 거울처럼 따른다(McpPatchContractTest) — 필드를 더하면
 * 그쪽도 자란다.
 */
public record ContentPageSaveRequest(
        @NotBlank @Size(max = ContentSlug.MAX_LENGTH) @Pattern(regexp = ContentSlug.PATTERN)
                String slug,
        @NotBlank @Size(max = 200) String ttl,
        @NotNull String mtxt) {}
