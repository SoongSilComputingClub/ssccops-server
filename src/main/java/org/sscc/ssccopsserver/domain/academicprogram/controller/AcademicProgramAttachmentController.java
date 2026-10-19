package org.sscc.ssccopsserver.domain.academicprogram.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.academicprogram.service.AcademicProgramAttachmentService;
import org.sscc.ssccopsserver.domain.form.dto.ProposalAttachmentDownloadResponse;
import org.sscc.ssccopsserver.domain.form.dto.ProposalAttachmentListResponse;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

/*
 * 학술 활동의 기획안 첨부 자료 (server#685 · ssccops#607) — 읽기 둘뿐이다. 첨부를 올리고 지우는 자리는
 * 기획안 응답(작성자 경로)이고 승인된 뒤에는 잠긴다 — 활동 쪽에서 고치는 요구가 생기면 그때 활동 대상의
 * 첨부를 따로 둔다(ssccops#607 «범위 밖»).
 *
 * 문턱은 GET /v1/academic-programs/{id}와 같다 — 인증 + 회원 행(@CurrentMember가 미가입을 403
 * SIGNUP_REQUIRED로 끊는다). 그래서 viewer는 받기만 하고 서비스로 넘기지 않는다: 회원이면 누구나 보는
 * 자료라 «누가»에 따라 갈리는 판정이 없다. @RequireAuthority를 붙이지 않는 것도 상세와 같다.
 */
@Tag(name = "Academic Program Attachments", description = "학술 활동이 태어난 기획안의 첨부 자료")
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/academic-programs/{academicProgramId}/attachments")
public class AcademicProgramAttachmentController {

    private final AcademicProgramAttachmentService academicProgramAttachmentService;

    @Operation(
            summary = "활동의 기획안 첨부 목록",
            description =
                    "이 활동이 태어난 기획안 응답(acdm_actv.form_rspns_id)의 첨부 — 승인 때 복사하지 않고 원 응답의"
                            + " 첨부를 그대로 읽는다. 이름·크기·올린 시각(발급 순)과 사용량을 싣는다. 가입한 회원이면"
                            + " 누구나 본다(활동 상세와 같은 문턱). 없는 활동은 404.")
    @GetMapping
    public ApiResponse<ProposalAttachmentListResponse> list(
            @PathVariable Long academicProgramId, @CurrentMember MemberEntity viewer) {
        return ApiResponse.success(academicProgramAttachmentService.list(academicProgramId));
    }

    @Operation(
            summary = "활동의 기획안 첨부 내려받기 URL",
            description =
                    "원본 이름이 붙은 15분짜리 서명 GET URL(Content-Disposition: attachment). 그 활동의 원 응답에"
                            + " 붙은 파일이 아니면 404 PROPOSAL_ATTACHMENT_NOT_FOUND — 다른 활동·응답의 파일 id로는"
                            + " 열리지 않는다. 링크로 저장하지 말 것.")
    @GetMapping("/{fileId}/download-url")
    public ApiResponse<ProposalAttachmentDownloadResponse> downloadUrl(
            @PathVariable Long academicProgramId,
            @PathVariable Long fileId,
            @CurrentMember MemberEntity viewer) {
        String url = academicProgramAttachmentService.downloadUrlOf(academicProgramId, fileId);
        return ApiResponse.success(
                new ProposalAttachmentDownloadResponse(
                        url, academicProgramAttachmentService.downloadUrlTtlSeconds()));
    }
}
