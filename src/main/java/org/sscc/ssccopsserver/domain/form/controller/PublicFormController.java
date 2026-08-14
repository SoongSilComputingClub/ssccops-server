package org.sscc.ssccopsserver.domain.form.controller;

import java.net.URI;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseSubmitRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseSubmitResponse;
import org.sscc.ssccopsserver.domain.form.dto.PublicFormResponse;
import org.sscc.ssccopsserver.domain.form.service.FormResponseService;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 공개 폼 조회·응답 제출 API (#35). 공개 링크(/f/{formId})로 들어온 응답자가 소비한다.
 *
 * 운영자용 FormController와 컨트롤러를 나눈 이유는 소비자가 다르기 때문이다. 폼 관리 화면은
 * 폼을 만들고 고치는 쪽이고 여기는 답을 내는 쪽이라, 응답 스키마도 권한도 앞으로 같이 움직이지
 * 않는다 — 한 클래스에 두면 운영자용 상세에 필드가 하나 늘 때마다 공개 링크로 새어 나갈 것이
 * 함께 는다.
 *
 * **두 경로 모두 인증이 필요하다.** '공개'는 누구나 링크를 열 수 있다는 뜻이지 익명으로 제출할
 * 수 있다는 뜻이 아니다 — 응답자는 Google OAuth 회원가입을 먼저 마친 회원이며(ssccops #61),
 * 그래서 form_rspns_hstry.mbr_id가 NOT NULL을 유지한다. SecurityConfig의 permitAll 목록에
 * (Swagger·헬스 프로브뿐이다) 이 경로가 들어가지 않는지 확인할 것.
 *
 * 등급 제한은 두지 않는다. 가입 직후의 임시회원(TEMP)도 응답할 수 있어야 하며, 미가입 주체는
 * @CurrentMember 리졸버가 403 SIGNUP_REQUIRED로 끊는다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/forms")
public class PublicFormController {

    private final FormResponseService formResponseService;

    /*
     * 응답자용 폼 조회. 운영자용 상세(GET /v1/forms/{formId})와 경로도 응답도 나눈다.
     *
     * 접수 가능하지 않으면 문항을 빼고 200을 주는 것이 아니라 409로 끊는다 — DRAFT 폼의 문항이
     * 링크만으로 새어 나가지 않게 하는 것이 이 엔드포인트의 첫 번째 책임이라, 문항을 실을지
     * 말지를 응답 조립의 분기 하나에 맡기지 않는다.
     */
    @Operation(
            summary = "응답자용 공개 폼 조회",
            description =
                    "공개 링크로 들어온 응답자가 폼과 문항 구성을 받아 간다. **인증이 필요하다** —"
                            + " '공개'는 누구나 링크를 열 수 있다는 뜻이지 익명으로 낼 수 있다는 뜻이 아니다."
                            + " 지금 응답을 받지 않는 폼(DRAFT·CLOSED·접수 기간 밖)은 문항을 내려주지 않고"
                            + " 409 FORM_NOT_ACCEPTING으로 응답한다. 없는 폼은 404 NOT_FOUND다."
                            + " alreadySubmitted가 true면 웹은 작성 화면 대신 제출 내역 화면을 보여준다"
                            + " (임시저장 응답은 제출로 치지 않는다).")
    @GetMapping("/{formId}/public")
    public ApiResponse<PublicFormResponse> getPublicForm(
            @PathVariable Long formId, @CurrentMember MemberEntity respondent) {
        return ApiResponse.success(formResponseService.getPublicForm(formId, respondent));
    }

    /*
     * 응답 제출. 새 자원을 만드는 요청이라 201이며 Location은 만들어진 응답을 가리킨다.
     *
     * 응답자(mbrId)·상태(rspnsSttsCd)·제출 일시(sbmsnDt)는 본문에서 받지 않는다 (LY-05).
     */
    @Operation(
            summary = "공개 폼 응답 제출",
            description =
                    "본문에는 답(rspnsCn)만 담는다. 응답자는 인증 주체에서, 상태(SUBMITTED)와 제출 일시는 서버가 채운다. 저장된 문항 구성으로"
                        + " 필수·형식·최대 선택 수를 다시 검사하며, 분기(branchMap)로 건너뛴 페이지의 필수 문항은 요구하지 않는다. 필수 누락은"
                        + " 400 REQUIRED_ANSWER_MISSING, 형식 불일치는 400 ANSWER_PATTERN_MISMATCH, 최대 선택"
                        + " 초과는 400 ANSWER_SELECTION_LIMIT_EXCEEDED, 폼에 없는 문항이 섞이면 400"
                        + " UNKNOWN_QUESTION_ITEM이다. 한 회원은 한 폼에 1건만 낼 수 있어 재제출은 409"
                        + " RESPONSE_ALREADY_SUBMITTED, 지금 응답을 받지 않는 폼은 409 FORM_NOT_ACCEPTING으로"
                        + " 응답한다. 빈 값(\"\"·[])인 문항은 저장하지 않는다.")
    @PostMapping("/{formId}/responses")
    public ResponseEntity<ApiResponse<FormResponseSubmitResponse>> submitFormResponse(
            @PathVariable Long formId,
            @Valid @RequestBody FormResponseSubmitRequest request,
            @CurrentMember MemberEntity respondent) {

        FormResponseSubmitResponse response =
                formResponseService.submitResponse(formId, request, respondent);
        URI location = URI.create("/v1/forms/" + formId + "/responses/" + response.formRspnsId());
        return ResponseEntity.created(location).body(ApiResponse.created(response));
    }
}
