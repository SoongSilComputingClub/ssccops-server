package org.sscc.ssccopsserver.domain.form.controller;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.form.dto.FormFromTemplateRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormFromTemplateResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormTemplateDetailResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormTemplateResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormTemplateSaveRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormTemplateUseRequest;
import org.sscc.ssccopsserver.domain.form.dto.TemplateFromFormRequest;
import org.sscc.ssccopsserver.domain.form.service.FormTemplateService;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.authorization.RequireAuthority;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 폼 템플릿 API (#142). 경로 버전 /v1을 쓰고 컨텍스트 경로에 /api를 두지 않는다 (AP-01).
 *
 * 클래스 레벨 @RequestMapping을 두지 않은 것은 FormLabelController와 같은 이유다. 이 컨트롤러는
 * /v1/form-templates(템플릿 자원)와 /v1/forms/{formId}/templates(폼의 하위 자원) 두 경로를 함께
 * 맡는다 — '이 폼을 템플릿으로 저장'은 경로상 폼의 하위 자원이지만 규칙은 전부 템플릿 쪽에
 * 있어서, 폼 컨트롤러에 두면 같은 규칙이 두 컨트롤러로 갈라진다.
 *
 * **인가는 클래스 레벨 @RequireAuthority(FORM_WRITE) 하나이며 조회도 예외로 두지 않는다.**
 * FormController가 핸들러마다 FORM_READ·FORM_WRITE·FORM_STATUS_CHANGE로 나눈 것과 갈리는데,
 * 근거는 FormResponseController가 클래스 레벨로 거는 것과 같다 — 핸들러가 하나 늘 때 애노테이션을
 * 빠뜨릴 자리를 만들지 않는다. 템플릿은 조회만 따로 떼어 줄 이유도 없다: 템플릿을 읽는 화면은
 * '템플릿에서 새 폼 시작하기'와 템플릿 관리뿐이고 둘 다 폼을 쓸 수 있는 사람의 화면이다.
 * FORM_WRITE는 시드에서 FORM_MANAGE 아래에 있어 '폼 관리'를 받은 역할이 그대로 닿는다.
 *
 * DELETE 핸들러는 없다. 내리는 것은 PATCH .../use이며 되돌릴 수 있어야 한다
 * (폼 라벨·하위 업무 유형과 같은 축).
 *
 * @CurrentMember는 생성자를 기록하는 세 경로만 받는다. 조회·수정·사용 여부 전환은 주체를
 * 남기지 않으므로 요구하지 않는다 — 미가입 주체는 어차피 클래스 레벨 인가에서 끊긴다
 * (권한이 없으므로 403).
 */
@RestController
@RequiredArgsConstructor
@RequireAuthority(AuthorityCode.FORM_WRITE)
public class FormTemplateController {

    private final FormTemplateService formTemplateService;

    /*
     * 템플릿 목록. 관리 화면은 비활성 템플릿도 함께 보여줘야 해서 기본이 전체이고,
     * '템플릿에서 시작하기' 화면은 ?useYn=true로 활성만 받는다.
     *
     * 문항 구성(qitemCpstCn)은 싣지 않는다 — 폼 목록과 같은 규칙이다. 대신 문항 수(qitemCnt)를
     * 실어 화면이 빈 템플릿과 완성된 템플릿을 구분할 수 있게 한다.
     *
     * 목록이지만 page 봉투를 싣지 않는다 (AP-11). 템플릿은 운영진이 손으로 만드는 데이터라
     * 수십 건을 넘지 않고 화면도 전체를 한 번에 그린다.
     */
    @Operation(
            summary = "폼 템플릿 목록 조회",
            description =
                    "템플릿 전체를 이름 오름차순으로 조회한다. useYn=true면 활성만, false면 비활성만 내려준다."
                            + " 문항 구성(qitemCpstCn)은 목록에 싣지 않으며 문항 수(qitemCnt)만 준다 —"
                            + " 구성이 필요하면 단건 조회를 부른다.")
    @GetMapping("/v1/form-templates")
    public ApiResponse<List<FormTemplateResponse>> getTemplates(
            @RequestParam(required = false) Boolean useYn) {
        return ApiResponse.success(formTemplateService.getTemplates(useYn));
    }

    @Operation(
            summary = "폼 템플릿 단건 조회",
            description =
                    "템플릿 상세·편집 화면이 진입 시 호출한다. 문항 구성을 통째로 싣고 있어 편집기가 그대로 초안으로"
                            + " 받아 쓴다. 없는 템플릿은 404 FORM_TEMPLATE_NOT_FOUND이며, 비활성 템플릿도 정상 조회된다.")
    @GetMapping("/v1/form-templates/{formTmplId}")
    public ApiResponse<FormTemplateDetailResponse> getTemplate(@PathVariable Long formTmplId) {
        return ApiResponse.success(formTemplateService.getTemplate(formTmplId));
    }

    @Operation(
            summary = "폼 템플릿 생성",
            description =
                    "새 템플릿을 만든다. 생성자(creatrMbrId)는 인증 주체에서 서버가 채우므로 요청 본문에 넣지 않으며"
                            + " 감사용이지 접근 제어에 쓰이지 않는다(템플릿은 공용이다). 새 템플릿은 항상 활성이다."
                            + " 문항 구성은 폼과 같은 검증기를 통과해야 하며 어기면 400"
                            + " INVALID_QUESTION_COMPOSITION으로 응답한다.")
    @PostMapping("/v1/form-templates")
    public ResponseEntity<ApiResponse<FormTemplateResponse>> createTemplate(
            @Valid @RequestBody FormTemplateSaveRequest request,
            @CurrentMember MemberEntity creator) {
        FormTemplateResponse response = formTemplateService.createTemplate(request, creator);
        URI location = URI.create("/v1/form-templates/" + response.formTmplId());
        return ResponseEntity.created(location).body(ApiResponse.created(response));
    }

    /*
     * 템플릿 수정. 문항 구성은 부분 갱신이 아니라 전체 교체라 PATCH가 아니라 PUT이다 (AP-06) —
     * 폼 수정과 같은 판단이다.
     */
    @Operation(
            summary = "폼 템플릿 수정",
            description =
                    "이름·설명·문항 구성을 통째로 교체한다. 사용 여부(useYn)는 이 API로 바꿀 수 없다 —"
                            + " PATCH /v1/form-templates/{formTmplId}/use를 쓴다."
                            + " 비활성 템플릿도 수정할 수 있다(비활성은 '새로 고를 수 없다'는 뜻이지 잠금이 아니다).")
    @PutMapping("/v1/form-templates/{formTmplId}")
    public ApiResponse<FormTemplateResponse> updateTemplate(
            @PathVariable Long formTmplId, @Valid @RequestBody FormTemplateSaveRequest request) {
        return ApiResponse.success(formTemplateService.updateTemplate(formTmplId, request));
    }

    /*
     * 사용 여부 전환. 값 하나만 바꾸므로 PUT이 아니라 PATCH이고, 별도 경로(/use)를 둔 것은
     * 수정(PUT)이 사용 여부를 건드리지 못하게 하기 위해서다 — 폼의 상태 전이를 전용 경로로
     * 분리한 것과 같은 갈래다. 라벨(PATCH /v1/form-labels/{id})과 달리 세그먼트를 하나 더 둔
     * 것은 이 컨트롤러의 PUT이 같은 자원 경로를 이미 쓰고 있어서다.
     */
    @Operation(
            summary = "폼 템플릿 사용 여부 전환",
            description =
                    "템플릿을 활성/비활성으로 전환한다. 비활성 템플릿으로는 새 폼을 만들 수 없지만(400"
                            + " FORM_TEMPLATE_NOT_USABLE) 조회·수정은 되고, 그 템플릿으로 이미 만들어 둔 폼은"
                            + " 아무 영향도 받지 않는다. 삭제 API는 두지 않는다.")
    @PatchMapping("/v1/form-templates/{formTmplId}/use")
    public ApiResponse<FormTemplateResponse> changeTemplateUsage(
            @PathVariable Long formTmplId, @Valid @RequestBody FormTemplateUseRequest request) {
        return ApiResponse.success(formTemplateService.changeTemplateUsage(formTmplId, request));
    }

    /*
     * 템플릿으로 새 폼 만들기. 새 폼이 생기므로 201이고 Location은 그 폼을 가리킨다 —
     * 만들어진 것은 템플릿의 하위 자원이 아니라 독립한 폼이다.
     *
     * 본문은 선택이다(required = false). 제목을 정하지 않고 바로 만드는 화면이 정상 경로이고,
     * 그때 빈 본문 {}를 억지로 보내게 하지 않는다.
     */
    @Operation(
            summary = "폼 템플릿으로 새 폼 만들기",
            description =
                    "템플릿의 문항 구성을 깊은 복사해 새 폼을 만든다. 상태는 DRAFT이고 접수 기간과 라벨은 비어 있다."
                            + " 제목(formTtlNm)은 선택이며 생략하면 템플릿명을 쓴다 — 본문 자체를 생략해도 된다."
                            + " 비활성 템플릿이면 400 FORM_TEMPLATE_NOT_USABLE, 없는 템플릿이면 404"
                            + " FORM_TEMPLATE_NOT_FOUND로 응답한다. 만들어진 폼은 이후 템플릿을 고쳐도 바뀌지 않는다.")
    @PostMapping("/v1/form-templates/{formTmplId}/forms")
    public ResponseEntity<ApiResponse<FormFromTemplateResponse>> createFormFromTemplate(
            @PathVariable Long formTmplId,
            @Valid @RequestBody(required = false) FormFromTemplateRequest request,
            @CurrentMember MemberEntity creator) {
        FormFromTemplateResponse response =
                formTemplateService.createFormFromTemplate(formTmplId, request, creator);
        URI location = URI.create("/v1/forms/" + response.formId());
        return ResponseEntity.created(location).body(ApiResponse.created(response));
    }

    /*
     * 이 폼의 문항 구성을 템플릿으로 저장. 경로는 폼의 하위 자원이지만 핸들러는 템플릿
     * 컨트롤러에 있다 (클래스 주석 참고). 새 템플릿이 생기므로 201이고 Location은 그 템플릿을
     * 가리킨다.
     *
     * 복수형(/templates)인 것은 한 폼에서 템플릿을 여러 번 저장할 수 있기 때문이다 — 폼을
     * 고칠 때마다 그 시점의 구성을 새 템플릿으로 남길 수 있고, 폼과 템플릿 사이에 1:1을
     * 강제할 근거가 없다.
     */
    @Operation(
            summary = "폼을 템플릿으로 저장",
            description =
                    "경로가 가리키는 폼의 **현재** 문항 구성을 새 템플릿으로 저장한다. 요청 본문은 문항을 싣지 않는다."
                            + " tmplNm을 생략하면 폼 제목을 쓰고 tmplExpln은 선택이며, 본문 자체를 생략해도 된다."
                            + " 접수 기간·상태·라벨·응답은 옮기지 않는다 — 템플릿에 그 자리가 없다."
                            + " 없는 폼은 404 NOT_FOUND로 응답한다.")
    @PostMapping("/v1/forms/{formId}/templates")
    public ResponseEntity<ApiResponse<FormTemplateResponse>> createTemplateFromForm(
            @PathVariable Long formId,
            @Valid @RequestBody(required = false) TemplateFromFormRequest request,
            @CurrentMember MemberEntity creator) {
        FormTemplateResponse response =
                formTemplateService.createTemplateFromForm(formId, request, creator);
        URI location = URI.create("/v1/form-templates/" + response.formTmplId());
        return ResponseEntity.created(location).body(ApiResponse.created(response));
    }
}
