package org.sscc.ssccopsserver.domain.operation.controller;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.operation.dto.OperationAttachmentDownloadResponse;
import org.sscc.ssccopsserver.domain.operation.dto.OperationAttachmentResponse;
import org.sscc.ssccopsserver.domain.operation.dto.OperationAttachmentUploadRequest;
import org.sscc.ssccopsserver.domain.operation.dto.OperationAttachmentUploadResponse;
import org.sscc.ssccopsserver.domain.operation.service.OperationAttachmentService;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

/*
 * 운영 건 첨부 (#493 · ssccops#410) — `/v1/operations/{operationId}/attachments`.
 *
 * 경로가 업무·하위 업무·회의가 아니라 **oper**인 것은 셋이 전부 oper의 확장이고 첨부가 그 공통 부모에
 * 붙기 때문이다. 화면은 상세 응답의 operationId로 부른다. 권한은 종류마다 그 건을 보는/고치는 권한을
 * 그대로 따르므로(OperationAttachmentAccessPolicy) 여기에는 @RequireAuthority가 없다 — 인증만 필요하다.
 *
 * 내려받기는 302가 아니라 JSON이다. 행사 이미지·갤러리는 <img>가 익명 경로를 따라가면 되지만 첨부는
 * 인증이 필요한 경로라 브라우저 이동으로는 헤더를 못 붙인다 — 화면이 서명 URL을 받아 그 주소로 간다.
 */
@Tag(name = "Operation Attachments", description = "운영 건(업무·하위 업무·회의) 첨부")
@RestController
@RequestMapping("/v1/operations/{operationId}/attachments")
@RequiredArgsConstructor
public class OperationAttachmentController {

    private final OperationAttachmentService attachmentService;

    @Operation(
            summary = "첨부 업로드 허가 발급",
            description =
                    "원본 파일 이름과 크기를 받아 R2 presigned PUT URL을 발급한다. 발급이 곧 첨부 한 건이며 PUT이"
                            + " 실패하면 DELETE로 치운다. 형식은 이름의 확장자로 판정(문서·표·발표·압축·이미지),"
                            + " 25MB 초과는 413 ATTACHMENT_TOO_LARGE. 권한은 그 건을 고칠 수 있는 사람"
                            + " — 업무 WORK_MANAGE · 하위 업무 담당자 또는 WORK_MANAGE · 회의 MEETING_MANAGE.")
    @PostMapping
    public ResponseEntity<ApiResponse<OperationAttachmentUploadResponse>> issueUploadUrl(
            @PathVariable Long operationId,
            @Valid @RequestBody OperationAttachmentUploadRequest request,
            @CurrentMember MemberEntity performer) {
        OperationAttachmentUploadResponse response =
                attachmentService.issueUploadUrl(operationId, request, performer);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @Operation(
            summary = "첨부 목록",
            description = "이름·크기·올린 사람·일시. 조회 권한(WORK_READ · MEETING_READ)이면 된다.")
    @GetMapping
    public ApiResponse<List<OperationAttachmentResponse>> list(
            @PathVariable Long operationId, @CurrentMember MemberEntity performer) {
        return ApiResponse.success(attachmentService.list(operationId, performer));
    }

    @Operation(
            summary = "첨부 내려받기 URL",
            description =
                    "원본 이름이 붙은 15분짜리 서명 URL. 302로 보내지 않는 것은 화면이 Bearer 헤더를 붙여야 이 경로를"
                            + " 부를 수 있어 브라우저 이동으로는 못 쓰기 때문이다 — JSON으로 받아 그 URL로 이동한다."
                            + " 링크로 저장하지 말 것.")
    @GetMapping("/{fileId}/download-url")
    public ApiResponse<OperationAttachmentDownloadResponse> downloadUrl(
            @PathVariable Long operationId,
            @PathVariable Long fileId,
            @CurrentMember MemberEntity performer) {
        String url = attachmentService.downloadUrlOf(operationId, fileId, performer);
        return ApiResponse.success(
                new OperationAttachmentDownloadResponse(
                        url, attachmentService.downloadUrlTtlSeconds()));
    }

    @Operation(summary = "첨부 삭제", description = "참조 행을 지우고 오브젝트는 커밋 뒤 지운다. 감사 로그에 남는다.")
    @DeleteMapping("/{fileId}")
    public ApiResponse<Void> delete(
            @PathVariable Long operationId,
            @PathVariable Long fileId,
            @CurrentMember MemberEntity performer) {
        attachmentService.delete(operationId, fileId, performer);
        return ApiResponse.success(null);
    }
}
