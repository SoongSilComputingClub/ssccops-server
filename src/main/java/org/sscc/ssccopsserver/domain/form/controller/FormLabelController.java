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
import org.sscc.ssccopsserver.domain.form.dto.FormLabelAssignRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormLabelAssignmentResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormLabelCreateRequest;
import org.sscc.ssccopsserver.domain.form.dto.FormLabelResponse;
import org.sscc.ssccopsserver.domain.form.dto.FormLabelUpdateRequest;
import org.sscc.ssccopsserver.domain.form.service.FormLabelService;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.authorization.RequireAuthority;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 폼 라벨 API (#34). 경로 버전 /v1을 쓰고 컨텍스트 경로에 /api를 두지 않는다 (AP-01).
 *
 * 클래스 레벨 @RequestMapping을 두지 않은 것은 의도된 것이다. 이 컨트롤러는 /v1/form-labels
 * (라벨 자원)와 /v1/forms/{formId}/labels (폼의 하위 자원) 두 경로를 함께 맡는다 — 지정 교체는
 * 경로상 폼의 하위 자원이지만 규칙은 전부 라벨 쪽에 있어서, 폼 컨트롤러(#32)에 두면 같은 규칙이
 * 두 컨트롤러로 갈라진다. 경로는 이슈의 계약대로 두고 핸들러만 여기 모았다.
 *
 * 인가는 핸들러마다 갈린다 (#9). 라벨 생성·사용 여부 변경은 FORM_LABEL_MANAGE이며 시드에서
 * EXECUTIVE 직속이라 회장·부회장·총무만 닿는다(정의서의 '최고운영자'를 권한으로 옮긴 것이다).
 * **목록 조회는 권한을 요구하지 않는다** — 폼 편집 화면의 라벨 선택기가 부르는 값이라 막으면
 * 폼을 만들 수 있는 사람이 라벨을 고르지 못한다. 폼의 라벨 지정 교체는 FORM_LABEL_MANAGE가
 * 아니라 FORM_WRITE다 — 바뀌는 것이 라벨 자체가 아니라 그 폼이기 때문이다.
 *
 * 모든 핸들러가 @CurrentMember를 받되 값을 쓰지 않는 것은 의도된 것이다. 라벨은 운영 데이터라
 * 미가입 사용자가 만질 수 없어야 하는데, 시큐리티 설정은 토큰 유효성(401)까지만 보고 가입
 * 여부는 보지 않는다 — 이 파라미터가 미가입 주체를 403 SIGNUP_REQUIRED로 끊는 자리다.
 * 라벨에는 등록자·변경자 컬럼이 없어 값 자체는 저장하지 않는다.
 */
@RestController
@RequiredArgsConstructor
public class FormLabelController {

    private final FormLabelService formLabelService;

    /*
     * 라벨 목록. 관리 화면은 비활성 라벨도 취소선으로 보여줘야 해서 기본이 전체이고,
     * 지정·필터 화면은 ?useYn=true로 활성만 받는다.
     *
     * 목록이지만 page 봉투를 싣지 않는다 (AP-11). 라벨은 운영진이 손으로 만드는 데이터라
     * 수십 건을 넘지 않고 화면도 전체를 한 번에 그리므로 페이징할 축이 없다.
     */
    @Operation(
            summary = "폼 라벨 목록 조회",
            description =
                    "라벨 전체를 이름 오름차순으로 조회한다. useYn=true면 활성 라벨만, false면 비활성 라벨만 내려준다."
                            + " 각 라벨의 usageCount는 그 라벨이 걸린 폼 수이며 비활성 라벨의 기존 지정도 포함한다.")
    @GetMapping("/v1/form-labels")
    public ApiResponse<List<FormLabelResponse>> getLabels(
            @RequestParam(required = false) Boolean useYn, @CurrentMember MemberEntity requester) {
        return ApiResponse.success(formLabelService.getLabels(useYn));
    }

    @Operation(
            summary = "폼 라벨 생성",
            description =
                    "새 라벨을 만든다. 생성된 라벨은 항상 활성(useYn=true)이며, 같은 이름이 이미 있으면"
                            + " 409 FORM_LABEL_NAME_DUPLICATED로 응답한다.")
    @RequireAuthority(AuthorityCode.FORM_LABEL_MANAGE)
    @PostMapping("/v1/form-labels")
    public ResponseEntity<ApiResponse<FormLabelResponse>> createLabel(
            @Valid @RequestBody FormLabelCreateRequest request,
            @CurrentMember MemberEntity registrant) {
        FormLabelResponse response = formLabelService.createLabel(request);
        URI location = URI.create("/v1/form-labels/" + response.formLblId());
        return ResponseEntity.created(location).body(ApiResponse.created(response));
    }

    @Operation(
            summary = "폼 라벨 사용 여부 변경",
            description =
                    "라벨을 활성/비활성으로 전환한다. 비활성 라벨은 새로 지정할 수 없고 필터 목록에서 빠지지만,"
                            + " 이미 폼에 걸린 지정은 그대로 유지된다. 삭제 API는 두지 않는다.")
    @RequireAuthority(AuthorityCode.FORM_LABEL_MANAGE)
    @PatchMapping("/v1/form-labels/{formLblId}")
    public ApiResponse<FormLabelResponse> updateLabelUsage(
            @PathVariable Long formLblId,
            @Valid @RequestBody FormLabelUpdateRequest request,
            @CurrentMember MemberEntity performer) {
        return ApiResponse.success(formLabelService.updateLabelUsage(formLblId, request));
    }

    /*
     * 폼의 라벨 지정 전체 교체. 경로는 폼의 하위 자원이지만 핸들러는 라벨 컨트롤러에 있다
     * (클래스 주석 참고).
     *
     * 생성이 아니라 교체이므로 200이다 (LY-06). 요청에 없는 라벨은 해제되고 빈 배열이면 전부 해제된다.
     */
    @Operation(
            summary = "폼의 라벨 지정 교체",
            description =
                    "폼에 걸린 라벨을 요청받은 목록으로 통째로 교체한다. 요청에 없는 라벨은 해제되고 빈 배열이면"
                            + " 전부 해제된다. 유지되는 지정은 지정 시각(crtDt)이 보존되며, 같은 요청을 두 번 보내도"
                            + " 결과가 같다. 비활성 라벨은 새로 추가할 때만 400 FORM_LABEL_NOT_USABLE로 막히고"
                            + " 이미 지정돼 있던 것은 유지된다.")
    @RequireAuthority(AuthorityCode.FORM_WRITE)
    @PutMapping("/v1/forms/{formId}/labels")
    public ApiResponse<List<FormLabelAssignmentResponse>> replaceFormLabels(
            @PathVariable Long formId,
            @Valid @RequestBody FormLabelAssignRequest request,
            @CurrentMember MemberEntity performer) {
        return ApiResponse.success(formLabelService.replaceFormLabels(formId, request.labelIds()));
    }
}
