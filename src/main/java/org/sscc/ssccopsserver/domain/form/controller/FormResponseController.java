package org.sscc.ssccopsserver.domain.form.controller;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseDetailResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseReviewRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseSummaryResponse;
import org.sscc.ssccopsserver.domain.form.dto.ProposalAttachmentDownloadResponse;
import org.sscc.ssccopsserver.domain.form.dto.ProposalAttachmentListResponse;
import org.sscc.ssccopsserver.domain.form.service.FormResponseReadPolicy;
import org.sscc.ssccopsserver.domain.form.service.FormResponseService;
import org.sscc.ssccopsserver.domain.form.service.ProposalAttachmentService;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.authorization.RequireAuthority;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;

import lombok.RequiredArgsConstructor;

/*
 * 운영자용 폼 응답 조회·심사 API (#37). 웹의 응답 목록·응답 상세·상태 변경 시트가 소비한다.
 *
 * **응답자용 PublicFormController와 컨트롤러를 나눈다.** 경로 접두사(/v1/forms/{formId}/responses)는
 * 같지만 소비자가 반대다 — 저쪽은 자기 답을 내는 사람이고 여기는 남의 답을 읽고 심사하는 사람이다.
 * 한 클래스에 두면 운영자용 응답에 필드가 하나 늘 때마다 공개 링크로 새어 나갈 것이 함께 늘고,
 * 인가 규칙도 클래스 하나에 두 벌을 적어야 한다 (#35에서 세운 분리와 같은 이유) — 실제로
 * 이 컨트롤러는 통째로 RESPONSE_REVIEW를 요구하고 저쪽은 권한 요구가 없다.
 *
 * 같은 경로에 메서드가 갈리는 자리가 하나 있다 — POST /v1/forms/{formId}/responses는 응답자의
 * 제출(#35)이고 GET은 운영자의 목록이다. 스프링은 메서드까지 보고 매핑하므로 충돌하지 않지만,
 * 두 파일 중 어느 쪽을 고칠지 헷갈리기 쉬운 자리라 여기에 적어 둔다.
 *
 * GET /{formRspnsId}가 GET /draft(#36, 응답자용)를 가로채지 않는 것도 같은 자리의 문제다.
 * 스프링은 경로 변수보다 리터럴 세그먼트를 먼저 고르므로 /responses/draft는 언제나 자동 저장
 * 조회로 간다 — 순서에 기대는 것이 아니라 명세로 정해진 동작이다.
 *
 * 인가는 RESPONSE_REVIEW 권한이며 **클래스 전체**에 건다 (#9). 이 API는 다른 회원의 학번·
 * 연락처·지원서 내용을 통째로 내려주므로, 핸들러가 하나 늘 때 애노테이션을 빠뜨리는 것만으로
 * 개인정보가 열리는 자리를 만들지 않는다. 조회와 심사를 나누지 않은 것은 남의 지원서를 읽는
 * 것 자체가 심사 권한이기 때문이다.
 *
 * ⚠️ #694(ssccops#614 · ADR-0068)부터 위 문단은 **검토에만** 사실이다. 조회 넷(목록 · 상세 · 첨부
 * 목록 · 내려받기)은 «RESPONSE_REVIEW, 또는 기획안(PROPOSAL) 폼이고 학술 조회 권한»이라 — 국원이
 * 기획안 검토를 읽기만 한다 — 경로의 폼에 따라 갈리는 판정을 애노테이션이 표현하지 못해
 * FormResponseReadPolicy가 맡는다(근거와 기각한 대안은 그 클래스 주석). 그래서 클래스 레벨
 * 애노테이션을 걷고 검토 핸들러에 메서드로 옮겼다. **새 핸들러는 둘 중 하나를 반드시 지닌다** —
 * @RequireAuthority, 또는 첫 줄의 readPolicy.requireReadable. 빠뜨린 핸들러는 인증만으로 열리며,
 * FormResponseControllerAuthorizationTest가 그것을 잡는다.
 *
 * 판정을 서비스가 아니라 여기서 부르는 것은 그것이 걷어 낸 애노테이션의 자리이기 때문이다 — 같은
 * 서비스 메서드(getResponses)를 학술 신청자 조회가 자기 판정을 마친 뒤 부른다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/forms/{formId}/responses")
public class FormResponseController {

    private final FormResponseService formResponseService;
    private final ProposalAttachmentService proposalAttachmentService;
    private final FormResponseReadPolicy readPolicy;

    /*
     * 응답 목록. 페이징을 두지 않고 배열을 그대로 내려준다 (FormResponseServiceImpl.getResponses 주석).
     *
     * statusCode를 생략하면 작성 중(DRAFT)을 뺀 전부다. "전체"에 DRAFT가 들어가지 않는다는 것이
     * 이 API의 기본값이며, 그 응답들은 statusCode=DRAFT로 명시했을 때만 나온다.
     */
    @Operation(
            summary = "폼 응답 목록 조회",
            description =
                    "운영자의 응답 목록 표가 소비한다. 응답자 정보(회원_명·학번·학과·등급·상태)는 응답에 복사돼 있지 않고"
                            + " mbr에서 조인해 내려주므로, 회원이 정보를 고치면 목록도 함께 바뀐다."
                            + " statusCode를 생략하면 **작성 중(DRAFT)을 뺀 전부**이며 작성 중 응답은 statusCode=DRAFT를"
                            + " 명시했을 때만 나온다 — 제출 전 답안이 심사 대기 목록에 섞이지 않게 하는 규칙이다."
                            + " 응답 내용(rspnsCn)은 싣지 않되 **대표 문항의 답(responseTitle)** 한 줄은 싣는다 —"
                            + " 기획안 폼이면 활동명이며, 그 폼의 대표 문항이 무엇인지는 서버(SystemFormContract)가"
                            + " 선언한다. 선언이 없는 폼·비워 둔 답은 null이고 서버가 대체값을 만들지 않으므로,"
                            + " 화면은 값이 없으면 순번(rspnsSeq)만으로 표시한다."
                            + " 정렬은 제출 일시 내림차순이고, 제출 일시가 없는 작성 중 응답은 최종 수정 일시로 정렬한다."
                            + " 응답 내용(rspnsCn)은 목록에 싣지 않는다 — 상세에서만 준다."
                            + " 페이징은 두지 않는다(배열을 그대로 내려준다)."
                            + " 응답 심사(RESPONSE_REVIEW) 권한이 필요하고, 기획안(시스템 폼 PROPOSAL)의 응답은"
                            + " 학술 조회(ACADEMIC_PROGRAM_READ) 권한으로도 읽힌다 — 어느 쪽도 아니면 403"
                            + " AUTHORITY_REQUIRED다(없는 폼도 같다).")
    @GetMapping
    public ApiResponse<List<FormResponseSummaryResponse>> getFormResponses(
            @PathVariable Long formId,
            @RequestParam(required = false) ResponseStatus statusCode,
            @Parameter(hidden = true) @CurrentMember MemberEntity requester) {
        readPolicy.requireReadable(
                formId, requester, "FormResponseController.getFormResponses(..)");
        return ApiResponse.success(formResponseService.getResponses(formId, statusCode));
    }

    /*
     * 응답 상세. 경로에 formId가 함께 들어가는 것이 요점이다 — 응답 식별자만 보고 조회하면
     * 다른 폼의 지원자 답변과 개인정보가 그대로 새어 나간다.
     */
    @Operation(
            summary = "폼 응답 단건 조회",
            description =
                    "목록 항목에 응답 내용(rspnsCn)·응답자의 기수·학년·연락처와 인접 응답 식별자를 더해 내려준다."
                            + " prevFormRspnsId·nextFormRspnsId는 상세 화면의 '이전 · 다음' 이동에 쓰이며, 목록의 기본"
                            + " 조회와 같은 순서·같은 범위(작성 중 제외)에서 고른다 — 끝이면 null이다. 작성 중(DRAFT)"
                            + " 응답을 직접 열면 두 값 모두 null이다(심사 목록에 들어 있지 않다)."
                            + " 처리 이력(reviewHistories)을 시간순(처리 일시 오름차순)으로 함께 싣는다 —"
                            + " 제출 · 승인 · 수정요청 · 반려가 처리자_명·검토 의견·처리 일시와 함께 쌓이며,"
                            + " 아직 아무 처리도 없으면 빈 배열이다. sbmsnSeq는 현재 제출 회차(최초 1)이고"
                            + " 이력의 각 줄에도 그 처리가 몇 회차에 대한 것이었는지 함께 실린다."
                            + " **다른 폼의 응답 식별자는 404 FORM_RESPONSE_NOT_FOUND다** — 없는 응답과 같은 코드로"
                            + " 내려, 그 폼에 그 번호가 있는지 없는지도 알려주지 않는다."
                            + " 자격은 목록과 같다 — RESPONSE_REVIEW, 또는 기획안(PROPOSAL)이면"
                            + " ACADEMIC_PROGRAM_READ. 연락처는 그와 별개로 MEMBER_MANAGE 보유자에게만 실린다.")
    @GetMapping("/{formRspnsId}")
    public ApiResponse<FormResponseDetailResponse> getFormResponse(
            @PathVariable Long formId,
            @PathVariable Long formRspnsId,
            @CurrentMember MemberEntity requester) {
        readPolicy.requireReadable(formId, requester, "FormResponseController.getFormResponse(..)");
        // 요청자를 넘기는 것은 연락처를 담을지 가르기 위해서다 (#277)
        return ApiResponse.success(formResponseService.getResponse(formId, formRspnsId, requester));
    }

    /*
     * 검토 처리 (#141). 상태와 검토 의견을 한 요청으로 받고 처리 이력을 한 줄 남긴다.
     *
     * **PATCH /{formRspnsId}/status를 대체한다.** 웹에서 이것은 시트 하나에 결과와 의견을 적고
     * 저장을 누르는 **한 번의 조작**이라 경로도 하나여야 한다 — 두 경로로 나누면 상태는 바뀌었는데
     * 사유가 없는 응답이 남을 수 있고, 그 응답은 이력 행이 잠겨 있어(updatable = false) 나중에
     * 채워 넣을 방법도 없다. 상태 한 필드를 고치는 요청이 아니라 '검토 처리'라는 사건을 남기는
     * 요청이 됐으므로 PATCH .../status가 아니라 POST .../reviews다.
     *
     * 새 자원의 URL을 돌려주지 않으므로 201이 아니라 200이다 — 이력을 단건으로 여는 경로가 없고
     * (상세가 통째로 싣는다), 폼 상태 전이(POST /v1/forms/{formId}/status)와 같은 모양이다.
     *
     * reviewer를 받는 것은 이제 **기록하기 위해서**다. #37에서는 @CurrentMember가 미가입 주체를
     * 403 SIGNUP_REQUIRED로 끊게 하려고 받아 두고 서비스로 넘기지 않았는데(넘기면 기록되는 것처럼
     * 읽힌다), 이 이슈가 남길 자리를 만들었으므로 그대로 넘긴다. 처리자를 요청 본문으로 받지
     * 않는 것은 #78이 세운 규칙과 같다 — 받아 주면 "누가 했는가"를 스스로 적어 넣을 수 있다.
     */
    @Operation(
            summary = "폼 응답 검토 처리",
            description =
                    "심사 결과와 검토 의견을 함께 반영하고 처리 이력(form_rspns_rvw_hstry)을 한 줄 남긴다."
                            + " rspnsSttsCd로 보낼 수 있는 값은 ACCEPTED(승인) · CHANGES_REQUESTED(수정요청) ·"
                            + " REJECTED(반려) 셋이다."
                            + " **수정요청·반려는 rvwOpnnCn이 필수이고 승인은 선택이다** — 비면 400"
                            + " REVIEW_OPINION_REQUIRED(공백만 있는 문자열도 같다)."
                            + " **승인·반려는 종결이라 되돌릴 수 없다** — ACCEPTED·REJECTED인 응답에 다시 검토를"
                            + " 걸면 400 INVALID_RESPONSE_STATUS_TRANSITION이다(승인 직후 후속 처리가 시작되므로"
                            + " 번복을 허용하면 이미 만들어진 것들을 되돌릴 방법이 없다). 아직 결론이 나지 않은"
                            + " SUBMITTED에서는 세 결론 중 무엇이든, CHANGES_REQUESTED에서는 ACCEPTED·REJECTED만"
                            + " 고를 수 있다."
                            + " 같은 상태로의 재지정, DRAFT가 얽힌 전이, 미심사(SUBMITTED)로 되돌리기도 같은"
                            + " 400이다 — SUBMITTED로 돌아가는 길은 응답자의 재제출뿐이다."
                            + " 기준 코드 밖의 값은 400 INVALID_CODE_VALUE, 없는 응답과 다른 폼의 응답 식별자는"
                            + " 404 FORM_RESPONSE_NOT_FOUND다."
                            + " 처리자는 요청 본문이 아니라 인증 주체(@CurrentMember)에서 온다."
                            + " 상태 변경과 이력 저장은 한 트랜잭션이라 이력이 저장되지 않으면 상태도 되돌아간다.")
    @PostMapping("/{formRspnsId}/reviews")
    @RequireAuthority(AuthorityCode.RESPONSE_REVIEW)
    public ApiResponse<FormResponseSummaryResponse> reviewFormResponse(
            @PathVariable Long formId,
            @PathVariable Long formRspnsId,
            @Valid @RequestBody FormResponseReviewRequest request,
            @CurrentMember MemberEntity reviewer) {
        return ApiResponse.success(
                formResponseService.reviewResponse(formId, formRspnsId, request, reviewer));
    }

    /*
     * ── 기획안 첨부 자료 — 검토자 경로 (server#685 · ssccops#607) ─────────────────
     * 학술국장이 개설을 허가할지 정할 때 내려받아 본다. 이 컨트롤러에 둔 것은 **클래스 단위
     * RESPONSE_REVIEW가 그대로 걸리게** 하기 위해서다 — 첨부도 남의 지원서의 일부라 같은 문턱이어야
     * 하고, 따로 컨트롤러를 만들면 애노테이션 한 줄을 빠뜨리는 것으로 열린다. 응답 상태와 무관하게
     * 읽는다(검토 중·반려된 것도). 폼 경계는 응답 상세와 같다 — 다른 폼의 응답 id는 404.
     * #694부터 그 문턱은 응답 상세와 같은 readPolicy다(클래스 주석) — 첨부를 볼 수 있는 사람과
     * 응답을 볼 수 있는 사람이 갈리면 검토 화면이 반쯤 열린다.
     */
    @Operation(
            summary = "기획안 첨부 목록(검토자)",
            description =
                    "응답에 붙은 첨부의 이름·크기·올린 시각(발급 순)과 사용량(totalSize · maxTotalSize ·"
                            + " maxCount). RESPONSE_REVIEW가 필요하다(없으면 403 FORBIDDEN) — 기획안(PROPOSAL)"
                            + " 응답이면 학술 조회(ACADEMIC_PROGRAM_READ)로도 된다. 응답 상태와 무관하다."
                            + " 기획안이 아닌 폼의 응답은 첨부가 생길 길이 없어 빈 목록이다. 다른 폼의 응답 id는 404"
                            + " FORM_RESPONSE_NOT_FOUND.")
    @GetMapping("/{formRspnsId}/attachments")
    public ApiResponse<ProposalAttachmentListResponse> getAttachments(
            @PathVariable Long formId,
            @PathVariable Long formRspnsId,
            @Parameter(hidden = true) @CurrentMember MemberEntity requester) {
        readPolicy.requireReadable(formId, requester, "FormResponseController.getAttachments(..)");
        return ApiResponse.success(proposalAttachmentService.listForReview(formId, formRspnsId));
    }

    @Operation(
            summary = "기획안 첨부 내려받기 URL(검토자)",
            description =
                    "원본 이름이 붙은 15분짜리 서명 GET URL(Content-Disposition: attachment). 그 응답에 붙은"
                            + " 파일이 아니면 404 PROPOSAL_ATTACHMENT_NOT_FOUND — 다른 응답의 파일 id로는 열리지"
                            + " 않는다. 링크로 저장하지 말 것.")
    @GetMapping("/{formRspnsId}/attachments/{fileId}/download-url")
    public ApiResponse<ProposalAttachmentDownloadResponse> getAttachmentDownloadUrl(
            @PathVariable Long formId,
            @PathVariable Long formRspnsId,
            @PathVariable Long fileId,
            @Parameter(hidden = true) @CurrentMember MemberEntity requester) {
        readPolicy.requireReadable(
                formId, requester, "FormResponseController.getAttachmentDownloadUrl(..)");
        String url = proposalAttachmentService.downloadUrlForReview(formId, formRspnsId, fileId);
        return ApiResponse.success(
                new ProposalAttachmentDownloadResponse(
                        url, proposalAttachmentService.downloadUrlTtlSeconds()));
    }
}
