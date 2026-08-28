package org.sscc.ssccopsserver.domain.form.controller;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseDraftRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseDraftResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseSubmitRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormResponseSubmitResponse;
import org.sscc.ssccopsserver.domain.form.dto.MyFormResponseDetailResponse;
import org.sscc.ssccopsserver.domain.form.dto.MyFormResponseSummaryResponse;
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
 * 이 경로가 들어가지 않는지 확인할 것 — 그쪽에는 Swagger·헬스 프로브와 /public/v1/**
 * (익명 행사 조회, ssccops#143)만 있고, 이 컨트롤러의 경로는 /v1 아래라 접두사부터 갈린다.
 *
 * 등급 제한은 두지 않는다. 가입 직후의 임시회원(TEMP)도 응답할 수 있어야 하며, 미가입 주체는
 * @CurrentMember 리졸버가 403 SIGNUP_REQUIRED로 끊는다.
 *
 * **권한(@RequireAuthority)도 요구하지 않는다** (#9). 여기는 응답자용이고 권한은 운영자의
 * 어휘라, 하나라도 걸면 지원자 전원이 지원서를 낼 수 없게 된다. 운영자용 FormController·
 * FormResponseController와 경로 접두사가 겹치므로 이쪽에 잘못 옮겨 붙이지 않도록 적어 둔다.
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
                            + " **alreadySubmitted는 '냈는가'가 아니라 '더 낼 수 없는가'다** — 다중 응답을 허용하는"
                            + " 폼(mltplRspnsYn = true)에서는 이미 냈어도 false이며, 그 화면은 작성 폼을 계속 보여줘야"
                            + " 한다. true면 웹은 작성 화면 대신 제출 내역 화면을 보여준다(임시저장 응답은 제출로 치지"
                            + " 않는다). myResponseCount는 내가 낸 건수(임시저장 제외)이고 submittedAt은 마지막 제출"
                            + " 일시라, 다중 응답 폼에서는 alreadySubmitted가 false인데 값이 있을 수 있다.")
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
                        + " UNKNOWN_QUESTION_ITEM이다. **몇 건까지 낼 수 있는지는 폼이 정한다(mltplRspnsYn)** — 허용하지"
                        + " 않는 폼에 다시 내면 409 RESPONSE_ALREADY_SUBMITTED(반려된 응답은 409"
                        + " RESPONSE_ALREADY_REJECTED)이고, 허용하는 폼이면 새 응답으로 접수되며 rspnsSeq(응답 순번)가 1"
                        + " 는다. 임시저장이나 수정요청받은 응답이 있으면 새로 만들지 않고 그 응답을 낸 것이 된다 (그때는 rspnsSeq가 그대로이고"
                        + " 제출 회차만 오른다). 지금 응답을 받지 않는 폼은 409 FORM_NOT_ACCEPTING으로 응답한다. **다만 수정요청받은"
                        + " 응답의 재제출은 접수 마감에 막히지 않는다** — 검토가 접수 뒤에 이뤄지는 폼(기획안)에서는 마감 후에 수정요청이 나가고,"
                        + " 그때 재제출까지 막으면 응답자에게 다시 낼 길이 없다. 새 응답 제출은 초안을 내는 것을 포함해 종전대로 마감 판정을 탄다. 빈"
                        + " 값(\"\"·[])인 문항은 저장하지 않는다.")
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

    /*
     * 내 응답 목록 (#143). 다중 응답을 허용하는 폼에서 "내가 지금까지 낸 것들"을 보는 경로다.
     *
     * 경로에 mbrId를 두지 않는 것은 자동 저장(#36)이 세운 규칙 그대로다 — 대상은 언제나 인증
     * 주체 본인이며, 받을 자리를 만들지 않는 것이 남의 응답에 닿는 경로를 막는 방법이다. 남의
     * 응답을 읽는 길은 RESPONSE_REVIEW 권한이 걸린 운영자용 경로(FormResponseController) 하나여야
     * 한다.
     *
     * **리터럴 mine 세그먼트가 운영자용 GET /{formRspnsId}를 가로채지 않는다.** 스프링이 경로 변수보다 리터럴
     * 세그먼트를 먼저 고르므로 /responses/mine은 언제나 이쪽으로 온다 — /responses/draft(#36)가
     * 같은 자리에서 같은 이유로 안전한 것과 같으며, 순서에 기대는 것이 아니라 명세로 정해진
     * 동작이다. 두 컨트롤러가 경로 접두사를 공유하므로 헷갈리기 쉬운 자리라 적어 둔다.
     *
     * 접수가 끝난 폼에서도 조회된다 — 자동 저장 조회와 갈리는 지점이며 근거는 서비스 주석에 있다.
     */
    @Operation(
            summary = "내 응답 목록 조회",
            description =
                    "응답자 본인이 이 폼에 낸 응답을 순번(rspnsSeq) 오름차순으로 내려준다. 대상은 언제나 인증 주체"
                            + " 본인이라 경로에 회원 식별자를 두지 않는다. 한 건도 없으면 빈 배열이다."
                            + " **작성 중(DRAFT) 응답도 포함한다** — 운영자용 목록이 DRAFT를 빼는 것과 기준이 다르며,"
                            + " 내 것을 나에게 숨길 이유가 없기 때문이다(그 응답은 sbmsnDt가 null이다)."
                            + " rspnsSeq(응답 순번)와 sbmsnSeq(제출 회차)는 **다른 값이다** — 앞은 몇 번째 응답인가이고"
                            + " 뒤는 그 응답을 몇 번 냈는가다(수정요청 뒤 재제출하면 뒤만 오른다)."
                            + " 응답 내용(rspnsCn)은 싣지 않는다. 접수가 끝났거나 아직 열지 않은 폼도 409가 아니라"
                            + " 200으로 답한다 — 자기가 낸 것을 확인하는 조회라 접수 가능 여부와 무관하다."
                            + " 없는 폼은 404 NOT_FOUND다.")
    @GetMapping("/{formId}/responses/mine")
    public ApiResponse<List<MyFormResponseSummaryResponse>> getMyFormResponses(
            @PathVariable Long formId, @CurrentMember MemberEntity respondent) {
        return ApiResponse.success(formResponseService.getMyResponses(formId, respondent));
    }

    /*
     * 내 응답 상세 (#177). 수정요청 사유를 읽고 이전 답을 불러오는 경로다.
     *
     * #141이 검토 처리 이력과 재제출 흐름을 만들었지만 제출자 쪽 화면 경로는 열지 않았다 —
     * 응답 내용은 내 응답 목록(#143)이 싣지 않고, 검토 이력을 실은 상세는 운영자용이라 클래스
     * 레벨 RESPONSE_REVIEW에 막혀 본인도 읽지 못했다. 그 사이가 이 핸들러다.
     *
     * **운영자용 GET .../responses/{formRspnsId}와 경로가 갈리는 자리다.** mine 세그먼트가 하나
     * 더 있어 애초에 다른 경로이며, /responses/mine(목록)과도 세그먼트 수로 갈린다 — 리터럴이
     * 경로 변수를 이긴다는 규칙(/draft · /mine)에 기대는 것이 아니라 서로 다른 패턴이다.
     *
     * 권한(@RequireAuthority)을 요구하지 않는 것은 이 컨트롤러의 다른 핸들러와 같다. 대신
     * **응답자 본인의 행만 조회된다**(서비스가 회원까지 걸어 찾는다) — "본인 또는 관리 권한"은
     * 애노테이션으로 표현되지 않으므로 서비스에서 끊는다(#139 승인 이력 조회의 선례).
     */
    @Operation(
            summary = "내 응답 상세 조회",
            description =
                    "응답자 본인이 낸 응답 한 건의 **내용(rspnsCn)과 검토 처리 이력(reviewHistories)**을 함께"
                            + " 받아 간다. 수정요청을 받은 응답을 다시 낼 때 웹이 이 응답으로 사유를 보여주고 이전 답을"
                            + " 프리필한다 — 재제출(POST /v1/forms/{formId}/responses)은 전체 본문을 다시 보내는"
                            + " 방식이라 그 프리필이 없으면 응답자가 처음부터 다시 쳐야 한다. 이력은 처리 일시 오름차순이고"
                            + " 처리가 없으면 빈 배열이며, **제출(SUBMIT) 행도 함께 실려** 타임라인이 \"제출 → 수정요청 →"
                            + " 재제출 → 승인\"으로 읽힌다(각 줄의 sbmsnSeq가 몇 회차에 대한 처리였는지 가리킨다)."
                            + " 대상은 언제나 인증 주체 본인이라 경로에 회원 식별자를 두지 않으며, **본인 응답이 아니면"
                            + " 없는 응답과 같은 404 FORM_RESPONSE_NOT_FOUND다** — 코드를 나누면 그 번호의 응답이"
                            + " 존재하는지가 새어 나간다. 운영자용 상세와 달리 인접 응답 식별자(prev·next)와 응답자"
                            + " 정보는 싣지 않는다(남의 응답 식별자이거나 요청 주체 본인의 값이다)."
                            + " 작성 중(DRAFT) 응답도 조회되고, 접수가 끝났거나 아직 열지 않은 폼도 409가 아니라 200이다"
                            + " — 자기가 낸 것을 확인하는 조회라 접수 가능 여부와 무관하며, 수정요청 사유를 읽는 시점은"
                            + " 대개 접수가 끝난 뒤다. 없는 폼은 404 NOT_FOUND다.")
    @GetMapping("/{formId}/responses/mine/{formRspnsId}")
    public ApiResponse<MyFormResponseDetailResponse> getMyFormResponse(
            @PathVariable Long formId,
            @PathVariable Long formRspnsId,
            @CurrentMember MemberEntity respondent) {
        return ApiResponse.success(
                formResponseService.getMyResponse(formId, formRspnsId, respondent));
    }

    /*
     * 내 작성 중 응답 조회 (#36). 재접속했을 때 이어서 쓰기 위한 복원 경로다.
     *
     * 경로에 mbrId가 없다. 대상은 언제나 인증 주체 본인이며, 식별자를 받지 않는 것이 아니라 받을
     * 자리를 만들지 않는 것이 요점이다 — 자리가 있으면 남의 작성 중 응답에 닿는 경로가 생기고,
     * 그때부터 그 경로를 막는 일은 인가 검사 한 줄이 빠지지 않는지에 달린다. 지원서 초안은 제출
     * 전이라 응답자 본인 말고는 아무도 볼 이유가 없는 개인정보다.
     *
     * 작성 중인 응답이 없으면 204가 아니라 data가 null인 200이다. 이 API의 모든 응답은
     * ApiResponse 봉투를 쓰는데 204는 본문 자체가 없어, 웹의 공통 응답 처리(봉투를 벗겨
     * data를 꺼낸다)가 이 엔드포인트 하나만 예외로 다뤄야 한다. '작성 중인 것이 없다'는 것도
     * 오류가 아니라 정상적인 조회 결과이므로 다른 조회와 같은 모양으로 답한다.
     */
    @Operation(
            summary = "내 작성 중 응답 조회",
            description =
                    "재접속한 응답자가 임시저장(DRAFT)해 둔 내용을 받아 이어서 작성한다. 대상은 언제나 인증 주체 본인이라"
                            + " 경로에 회원 식별자를 두지 않는다. 작성 중인 응답이 없으면 data가 null인 200으로"
                            + " 응답한다 — 이미 제출을 마친 경우도 '작성 중인 것이 없다'로 같다(제출 여부는 공개 폼"
                            + " 조회의 alreadySubmitted가 전한다). 지금 응답을 받지 않는 폼은 409 FORM_NOT_ACCEPTING,"
                            + " 없는 폼은 404 NOT_FOUND다.")
    @GetMapping("/{formId}/responses/draft")
    public ApiResponse<FormResponseDraftResponse> getMyDraft(
            @PathVariable Long formId, @CurrentMember MemberEntity respondent) {
        return ApiResponse.success(
                formResponseService.findMyDraft(formId, respondent).orElse(null));
    }

    /*
     * 작성 중 응답 저장 (#36). 자동 저장이 매 입력마다 부르는 경로다.
     *
     * POST가 아니라 PUT인 것은 이 요청이 몇 번을 보내도 결과가 같기 때문이다 — 회원당 폼당 행은
     * 하나이고 본문은 그 행의 내용을 통째로 대체한다. POST로 두면 자동 저장이 도는 동안 응답이
     * 계속 만들어지는 것처럼 읽히고, 201/Location을 매번 돌려줄지 같은 답 없는 질문이 따라온다.
     *
     * 저장 빈도(디바운스)는 웹이 조절한다. 서버는 매 요청을 그대로 처리하되 본문 크기에만 상한을
     * 둔다 (RESPONSE_CONTENT_TOO_LARGE).
     */
    @Operation(
            summary = "작성 중 응답 저장(자동 저장)",
            description =
                    "본문의 rspnsCn으로 작성 중인 응답을 통째로 대체한다(upsert) — 임시저장 행이 있으면 내용만 갱신하고, 없으면 DRAFT 상태로"
                        + " 새로 만든다. **초안은 폼 종류와 무관하게 언제나 최대 1건이라** 몇 번을 불러도 행이 늘지 않는다 (다중 응답 폼에서도"
                        + " 그렇다 — 초안을 제출해 자리가 빈 뒤에야 새 초안을 시작할 수 있다). **자동 저장은 필수·형식(정규식)·최대 선택 수를"
                        + " 검사하지 않는다** — 작성 중에는 비어 있거나 형식이 맞지 않는 것이 정상이고, 그 검사는 제출 시점의 몫이다. 다만 폼에"
                        + " 없는 문항이 섞이면 400 UNKNOWN_QUESTION_ITEM, 문항 유형과 맞지 않는 값은 400"
                        + " INVALID_ANSWER_VALUE, 응답 내용이 상한을 넘기면 413 RESPONSE_CONTENT_TOO_LARGE다."
                        + " 다시 낼 수 없는 폼(mltplRspnsYn = false)에 이미 제출했으면 409"
                        + " RESPONSE_ALREADY_SUBMITTED, 지금 응답을 받지 않는 폼은 409 FORM_NOT_ACCEPTING이며, 첫"
                        + " 저장이 동시에 도착해 부딪히면 409 RESPONSE_SAVE_CONFLICT로 재시도를 알린다.")
    @PutMapping("/{formId}/responses/draft")
    public ApiResponse<FormResponseDraftResponse> saveMyDraft(
            @PathVariable Long formId,
            @Valid @RequestBody FormResponseDraftRequest request,
            @CurrentMember MemberEntity respondent) {
        return ApiResponse.success(formResponseService.saveDraft(formId, request, respondent));
    }
}
