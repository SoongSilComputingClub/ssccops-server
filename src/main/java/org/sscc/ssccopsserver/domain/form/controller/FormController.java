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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.dto.FormDetailResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormDuplicateResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormSaveRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormSaveResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormStatusChangeRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormStatusChangeResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormSummaryResponse;
import org.sscc.ssccopsserver.domain.form.service.FormService;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.authorization.RequireAuthority;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 폼 API (#32). 경로 버전 /v1을 쓰고 컨텍스트 경로에 /api를 두지 않는다 (AP-01).
 * 폼 관리 화면 네 개(목록·상세·편집·복제 버튼)가 전부 이 컨트롤러를 소비한다.
 *
 * 인가는 핸들러마다 갈린다 (#9) — 조회는 FORM_READ, 생성·수정·복제는 FORM_WRITE, 접수 상태
 * 전이는 FORM_STATUS_CHANGE다. 셋 다 시드에서 FORM_MANAGE 아래에 있으므로 '폼 관리'를 통째로
 * 받은 역할은 셋 모두에 닿고, 필요하면 조회만 떼어 줄 수도 있다 — 클래스에 하나로 걸지 않은
 * 이유가 이것이다. **응답자용 PublicFormController에는 권한 요구가 없다**(인증만).
 *
 * 조회를 뺀 쓰기는 전부 @CurrentMember를 받는다. 생성·복제는 폼의 생성자를 서버가 채워야 해서고,
 * 수정은 문항 구성 이력의 변경자를 채워야 해서다(#140 — 그전까지 수정은 남기는 것이 없어 주체를
 * 요구하지 않았다). 상태 전이만 주체를 서비스로 넘기지 않는데, 폼 상태 이력 테이블이 없어 남길
 * 자리가 없기 때문이다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/forms")
public class FormController {

    private final FormService formService;

    /*
     * 폼 목록. 두 필터는 각각 선택이며 둘 다 주면 AND다.
     *
     * 문항 구성(qitemCpstCn)은 응답에 싣지 않는다 — 폼 하나에 문항이 수십 개면 목록 응답이
     * 그만큼 곱해져 비대해진다. 문항이 필요하면 단건 조회를 부른다.
     */
    @Operation(
            summary = "폼 목록 조회",
            description =
                    "폼 관리 화면의 카드 목록. statusCode·labelId는 각각 선택이며 둘 다 주면 AND로 걸린다. 응답"
                        + " 건수(responseCount)는 제출"
                        + " 이상(SUBMITTED·CHANGES_REQUESTED·ACCEPTED·REJECTED)만 세며 작성 중인 임시저장 응답은 세지"
                        + " 않는다. 목록에는 문항 구성(qitemCpstCn)을 싣지 않는다.")
    @RequireAuthority(AuthorityCode.FORM_READ)
    @GetMapping
    public ApiResponse<List<FormSummaryResponse>> getForms(
            @RequestParam(required = false) FormStatus statusCode,
            @RequestParam(required = false) Long labelId) {
        return ApiResponse.success(formService.getForms(statusCode, labelId));
    }

    /*
     * 상대로 시스템 폼이 요구하는 문항(systemRequiredQitemIds)을 싣는다 (#155). 목록에는 없고
     * 상세에만 있는 것은 문항 편집이 이 화면에서만 일어나기 때문이다.
     */
    @Operation(
            summary = "폼 단건 조회",
            description =
                    "폼 상세·편집 화면이 진입 시 호출한다. 문항 구성을 통째로 싣고 있어 편집기가 그대로 초안으로 받아 쓴다."
                            + " systemRequiredQitemIds는 코드가 이 폼에서 반드시 읽는 qitemId 목록이다 — 편집 화면은 이"
                            + " 문항들의 삭제를 미리 잠그면 된다. 시스템 폼이 아니거나 요구 문항이 없으면 빈 배열이며 null은"
                            + " 내려가지 않는다. 미리 잠그는 것은 편의일 뿐이고 지우고 저장하면 서버가 여전히 400"
                            + " SYSTEM_FORM_CONTRACT_VIOLATION으로 거절한다."
                            + " 없는 폼은 404 FORM_NOT_FOUND로 응답한다.")
    @RequireAuthority(AuthorityCode.FORM_READ)
    @GetMapping("/{formId}")
    public ApiResponse<FormDetailResponse> getForm(@PathVariable Long formId) {
        return ApiResponse.success(formService.getForm(formId));
    }

    @Operation(
            summary = "폼 생성",
            description =
                    "생성자(creatrMbrId)는 인증 주체에서 서버가 채우므로 요청 본문에 넣지 않는다."
                            + " 상태를 지정하지 않으면 DRAFT이며, 편집 화면의 '바로 접수 시작'은 OPEN을 보낸다."
                            + " mltplRspnsYn을 true로 두면 한 회원이 이 폼에 여러 건을 낼 수 있다(생략은 false ="
                            + " 회원당 1건). 지원서·설문은 false 그대로 두고, 스터디 제안처럼 한 사람이 두 개를"
                            + " 내는 것이 정상인 폼에만 켠다."
                            + " 문항 구성이 규칙을 어기면 400 INVALID_QUESTION_COMPOSITION,"
                            + " 접수 종료가 시작보다 빠르면 400 INVALID_RECEIPT_PERIOD로 응답한다.")
    @RequireAuthority(AuthorityCode.FORM_WRITE)
    @PostMapping
    public ResponseEntity<ApiResponse<FormSaveResponse>> createForm(
            @Valid @RequestBody FormSaveRequest request, @CurrentMember MemberEntity creator) {
        FormSaveResponse response = formService.createForm(request, creator);
        URI location = URI.create("/v1/forms/" + response.formId());
        return ResponseEntity.created(location).body(ApiResponse.created(response));
    }

    /*
     * 폼 수정. 문항 구성은 부분 갱신이 아니라 전체 교체라 PATCH가 아니라 PUT이다 (AP-06) —
     * 편집 자동 저장(ssccops #63)도 같은 엔드포인트를 그대로 쓴다.
     *
     * 본문의 formSttsCd는 무시한다 (#33). 자동 저장이 상세 응답을 그대로 되돌려 보내므로 그 값을
     * 받아 쓰면 타이핑 한 번이 접수 상태를 덮어쓴다 — 상태는 액션 경로에서만 바뀐다.
     *
     * @CurrentMember를 받는 것은 #140부터다. 문항 구성이 실제로 바뀐 저장은 form_qitem_hstry에
     * 한 행을 남기는데 그 변경자가 여기서 온다 — 요청 본문으로 받으면 남의 이름으로 이력을
     * 쓸 수 있어 이력이 증거가 되지 못한다 (#78이 세운 규칙과 같다).
     */
    @Operation(
            summary = "폼 수정",
            description =
                    "문항 구성과 라벨 지정을 통째로 교체한다. 상태(formSttsCd)는 이 API로 바꿀 수 없고"
                            + " 본문에 실려 와도 무시한다 — POST /v1/forms/{formId}/status를 쓴다."
                            + " labelIds를 생략하거나 빈 배열로 보내면 라벨을 모두 뗀다."
                            + " 이미 응답이 있는 폼에서 기존 qitemId를 지우거나 바꾸면 409 QUESTION_ITEM_IN_USE로"
                            + " 응답한다 — 응답 내용의 key가 qitemId라 끊기면 과거 응답을 읽을 수 없다."
                            + " 시스템 폼(sysYn = true)에서 코드가 요구하는 qitemId를 지우면 400"
                            + " SYSTEM_FORM_CONTRACT_VIOLATION이며, 문구 수정·문항 추가·순서 변경은 허용한다."
                            + " 문항 구성이 실제로 바뀐 저장에서만 qitemVer가 1 오르고 그 시점 구성이 이력에 남는다"
                            + " — 제목·접수 기간만 바꾼 저장에는 버전이 오르지 않는다."
                            + " **mltplRspnsYn(다중 응답 허용)은 이 API로 바꾼다** — 생략하면 false로 저장되므로"
                            + " 편집 자동 저장은 상세 응답에 실려 온 값을 그대로 함께 보내야 한다. 접수 중에도 바꿀 수"
                            + " 있고, 끄더라도 이미 들어온 응답은 지워지지 않는다(지금부터 새로 낼 수 없다는 뜻이다).")
    @RequireAuthority(AuthorityCode.FORM_WRITE)
    @PutMapping("/{formId}")
    public ApiResponse<FormSaveResponse> updateForm(
            @PathVariable Long formId,
            @Valid @RequestBody FormSaveRequest request,
            @CurrentMember MemberEntity actor) {
        return ApiResponse.success(formService.updateForm(formId, request, actor));
    }

    /*
     * 폼 복제. 원본을 바꾸지 않고 새 폼을 만드는 행위라 201이며 Location은 사본을 가리킨다.
     * 상태를 PUT으로 직접 쓰는 대신 행위 경로를 두는 것과 같은 이유로 /duplicate를 쓴다 (AP-03).
     */
    @Operation(
            summary = "폼 복제",
            description =
                    "제목에 '(복사본)'을 붙이고 상태 DRAFT·접수 일시 초기화로 새 폼을 만든다."
                            + " 문항 구성은 깊은 복사라 사본을 고쳐도 원본이 바뀌지 않는다."
                            + " 응답과 라벨은 승계하지 않으며 생성자는 복제를 수행한 회원이다."
                            + " 다중 응답 허용 여부(mltplRspnsYn)는 승계한다 — 제목·문항 구성과 같은 폼의 설정이다.")
    @RequireAuthority(AuthorityCode.FORM_WRITE)
    @PostMapping("/{formId}/duplicate")
    public ResponseEntity<ApiResponse<FormDuplicateResponse>> duplicateForm(
            @PathVariable Long formId, @CurrentMember MemberEntity creator) {
        FormDuplicateResponse response = formService.duplicateForm(formId, creator);
        URI location = URI.create("/v1/forms/" + response.formId());
        return ResponseEntity.created(location).body(ApiResponse.created(response));
    }

    /*
     * 폼 접수 상태 전이 (#33). 상세 화면의 '접수 시작 / 마감' 버튼과 편집 화면의 '바로 접수 시작'이
     * 이 하나의 액션 경로를 쓴다. 상태를 PUT /v1/forms/{formId}의 필드로 넘기는 경로는 두지
     * 않는다 (AP-03 · SubWorkController의 전이 경로 선례) — 문항을 고치는 것과 접수를 여는 것은
     * 권한·검증·감사 대상이 다르고, 편집 자동 저장(ssccops #63)이 매 타이핑마다 쏘는 PUT에
     * 상태가 실려 있으면 자동 저장이 접수 상태를 덮어쓴다.
     *
     * 상태 변경은 생성이 아니므로 200이다 (LY-06). 전이 가능 여부와 사전 검증은 도메인이
     * 판단하므로 여기서 분기하지 않는다 (LY-02).
     *
     * actor를 받는 것은 기록하기 위해서가 아니라 @CurrentMember가 미가입 주체를 403
     * SIGNUP_REQUIRED로 끊게 하기 위해서다 — 접수를 열고 닫는 것은 회원만 할 수 있어야 한다.
     * 폼 상태 이력 테이블이 없어 수행자를 남길 자리가 없으므로 서비스로는 넘기지 않는다.
     */
    @Operation(
            summary = "폼 접수 상태 전이",
            description =
                    "action은 OPEN 또는 CLOSE다. DRAFT→OPEN·OPEN→CLOSE·CLOSED→OPEN(마감 철회)만 허용하며"
                            + " 그 밖의 전이는 400 INVALID_FORM_STATUS_TRANSITION으로 응답한다."
                            + " 문항이 0개인 폼을 열려 하면 400 FORM_HAS_NO_QUESTION이다."
                            + " 응답의 receiptStatus는 상태와 접수 기간을 함께 본 파생 값으로,"
                            + " 기간이 끝난 폼은 formSttsCd가 OPEN인 채 EXPIRED가 된다 (자동 마감하지 않는다).")
    @RequireAuthority(AuthorityCode.FORM_STATUS_CHANGE)
    @PostMapping("/{formId}/status")
    public ApiResponse<FormStatusChangeResponse> changeFormStatus(
            @PathVariable Long formId,
            @Valid @RequestBody FormStatusChangeRequest request,
            @CurrentMember MemberEntity actor) {
        return ApiResponse.success(formService.changeStatus(formId, request));
    }
}
