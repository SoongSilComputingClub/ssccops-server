package org.sscc.ssccopsserver.domain.content.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.sscc.ssccopsserver.domain.content.code.ContentCategory;
import org.sscc.ssccopsserver.domain.content.code.ContentSlug;

/*
 * 포스트 생성·수정 본문 (ssccops#381). 페이지와 같은 규칙 — 생성과 수정이 같은 모양이고 수정은
 * 통째로 교체다. coverFileId는 이 포스트의 갤러리(file_rfrnc · CONTENT_POST)에 있는 파일이어야
 * 하며(400 COVER_NOT_IN_GALLERY) 생성 시점에는 갤러리가 없으므로 null이 정상이다. eventId는
 * 선택이며 서버가 존재를 검증하지 않는다 — 지운 행사를 가리켜도 www 링크가 404가 될 뿐이고,
 * 갈무리 글이 행사 삭제에 막혀서는 안 된다(FK는 실재만 본다).
 *
 * MCP의 ContentPostPatch가 이 record를 거울처럼 따른다(McpPatchContractTest).
 */
public record ContentPostSaveRequest(
        @NotBlank @Size(max = ContentSlug.MAX_LENGTH) @Pattern(regexp = ContentSlug.PATTERN)
                String slug,
        @NotNull ContentCategory cntntClsfCd,
        @NotBlank @Size(max = 200) String ttl,
        @Size(max = 300) String smry,
        @NotNull String mtxt,
        @NotNull LocalDate actvYmd,
        Long eventId,
        Long coverFileId) {}
