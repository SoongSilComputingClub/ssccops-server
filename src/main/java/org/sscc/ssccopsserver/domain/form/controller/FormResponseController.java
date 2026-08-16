package org.sscc.ssccopsserver.domain.form.controller;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseDetailResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseStatusChangeRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseSummaryResponse;
import org.sscc.ssccopsserver.domain.form.service.FormResponseService;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.authorization.RequireAuthority;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import io.swagger.v3.oas.annotations.Operation;

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
 */
@RestController
@RequiredArgsConstructor
@RequireAuthority(AuthorityCode.RESPONSE_REVIEW)
@RequestMapping("/v1/forms/{formId}/responses")
public class FormResponseController {

    private final FormResponseService formResponseService;

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
                            + " 정렬은 제출 일시 내림차순이고, 제출 일시가 없는 작성 중 응답은 최종 수정 일시로 정렬한다."
                            + " 응답 내용(rspnsCn)은 목록에 싣지 않는다 — 상세에서만 준다."
                            + " 페이징은 두지 않는다(배열을 그대로 내려준다).")
    @GetMapping
    public ApiResponse<List<FormResponseSummaryResponse>> getFormResponses(
            @PathVariable Long formId, @RequestParam(required = false) ResponseStatus statusCode) {
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
                            + " **다른 폼의 응답 식별자는 404 FORM_RESPONSE_NOT_FOUND다** — 없는 응답과 같은 코드로"
                            + " 내려, 그 폼에 그 번호가 있는지 없는지도 알려주지 않는다.")
    @GetMapping("/{formRspnsId}")
    public ApiResponse<FormResponseDetailResponse> getFormResponse(
            @PathVariable Long formId, @PathVariable Long formRspnsId) {
        return ApiResponse.success(formResponseService.getResponse(formId, formRspnsId));
    }

    /*
     * 응답 상태 변경(심사 결과 반영). 응답 자원의 한 필드만 바꾸므로 PUT이 아니라 PATCH다.
     *
     * actor를 받는 것은 기록하기 위해서가 아니라 @CurrentMember가 미가입 주체를 403
     * SIGNUP_REQUIRED로 끊게 하기 위해서다 (FormController.changeFormStatus와 같은 이유).
     * **응답 상태 이력 테이블이 없어 "누가 승인했는지"는 어디에도 남지 않는다** — mdfcn_dt만
     * 갱신되며, 수행자 기록은 감사 로그(#8)가 확정되면 그쪽에 얹는다. 그래서 서비스로는 넘기지
     * 않는다(넘겨 두면 기록되고 있는 것처럼 읽힌다).
     */
    @Operation(
            summary = "폼 응답 상태 변경",
            description =
                    "심사 결과를 반영한다. SUBMITTED ↔ ACCEPTED ↔ REJECTED는 자유롭게 오갈 수 있다(심사 번복 허용)."
                            + " **DRAFT가 얽힌 전이는 400 INVALID_RESPONSE_STATUS_TRANSITION으로 거절한다** —"
                            + " 작성 중 응답을 심사하면 응답자가 아직 쓰고 있던 내용이 확정되고, 제출된 응답을 DRAFT로"
                            + " 되돌리면 제출 일시가 남은 미제출 응답이 생긴다. DRAFT → SUBMITTED는 오직 응답자의"
                            + " 제출로만 일어난다. 기준 코드 밖의 값은 400 INVALID_CODE_VALUE, 없는 응답과 다른 폼의"
                            + " 응답 식별자는 404 FORM_RESPONSE_NOT_FOUND다."
                            + " **누가 상태를 바꿨는지는 기록되지 않는다** — 응답 상태 이력 테이블이 없어 mdfcn_dt만"
                            + " 갱신되며, 감사 로그(#8)가 확정되면 그쪽에 얹는다.")
    @PatchMapping("/{formRspnsId}/status")
    public ApiResponse<FormResponseSummaryResponse> changeFormResponseStatus(
            @PathVariable Long formId,
            @PathVariable Long formRspnsId,
            @Valid @RequestBody FormResponseStatusChangeRequest request,
            @CurrentMember MemberEntity actor) {
        return ApiResponse.success(
                formResponseService.changeResponseStatus(formId, formRspnsId, request));
    }
}
