package org.sscc.ssccopsserver.domain.operation.controller;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.operation.dto.AuthorizerAuthorityResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkTypeActivationRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkTypeResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkTypeSaveRequest;
import org.sscc.ssccopsserver.domain.operation.service.SubWorkTypeService;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.authorization.RequireAuthority;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 하위 업무 유형 API (OPS-018 · OPS-019 · #43). 경로 버전 /v1을 쓰고 컨텍스트 경로에
 * /api를 두지 않는다 (AP-01).
 *
 * 경로가 정의서의 /v1/operation-types가 아니라 /v1/sub-work-types인 것은 실제 리소스가
 * sub_work_type이고, 상위 업무의 업무유형(EVENT/REGULAR/ROUTINE)과 이름이 겹치기 때문이다.
 * 하위 업무 등록(OPS-007)도 이 유형을 subWorkTypeId로 참조한다.
 *
 * 인가는 핸들러마다 갈린다 (#9) — 조회는 SUB_WORK_TYPE_READ, 등록·수정·사용 여부 전환은
 * SUB_WORK_TYPE_MANAGE다. 정의서의 '조회 국장 이상 · 등록·수정 회장·부회장·총무'를 역할이
 * 아니라 권한으로 옮긴 것으로, 시드에서 READ는 OPERATOR 아래(국장이 닿음)에 있고 MANAGE는
 * EXECUTIVE 직속(회장·부회장·총무만 닿음)이다. 클래스에 하나로 걸지 않는 이유가 이것이다.
 *
 * 유형을 지우는 엔드포인트는 두지 않는다. 하위 업무가 FK로 참조하므로 하드 삭제가 불가능하고,
 * 화면의 관리 열에도 '삭제'가 없다 — 대신 사용 토글을 내린다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/sub-work-types")
public class SubWorkTypeController {

    private final SubWorkTypeService subWorkTypeService;

    /*
     * 유형 목록. 관리 화면은 필터 없이 부르고(비활성도 보여야 다시 켤 수 있다),
     * 하위 업무 등록 폼의 드롭다운은 useYn=true로 부른다.
     */
    @Operation(
            summary = "하위 업무 유형 목록 조회",
            description = "승인 정책 기준 데이터 목록. useYn을 생략하면 비활성 유형까지 전부, " + "true면 사용 중인 유형만 내려준다.")
    @RequireAuthority(AuthorityCode.SUB_WORK_TYPE_READ)
    @GetMapping
    public ApiResponse<List<SubWorkTypeResponse>> getSubWorkTypes(
            @RequestParam(required = false) Boolean useYn) {
        return ApiResponse.success(subWorkTypeService.getSubWorkTypes(useYn));
    }

    /*
     * 유형 폼의 승인자 선택지 (#123). 결재 권한 코드는 고정 어휘지만 표시명(authrt_nm)은
     * 운영 데이터라 서버가 합쳐 내린다. 권한 트리 API(/v1/authorities)는 ROLE_MANAGE로 잠겨
     * 있어 유형 관리 화면이 부를 수 없으므로 이 컨트롤러의 권한(SUB_WORK_TYPE_READ)으로 연다.
     */
    @Operation(summary = "승인자 결재 권한 목록 조회", description = "유형 등록·수정 폼의 승인자 선택지 — 결재 권한 코드와 표시명.")
    @RequireAuthority(AuthorityCode.SUB_WORK_TYPE_READ)
    @GetMapping("/authorizer-authorities")
    public ApiResponse<List<AuthorizerAuthorityResponse>> getAuthorizerAuthorities() {
        return ApiResponse.success(subWorkTypeService.getAuthorizerAuthorities());
    }

    @Operation(
            summary = "하위 업무 유형 추가",
            description = "승인 여부·승인자 결재 권한·의사결정·완료 점검 항목을 담은 새 유형을 만든다.")
    @RequireAuthority(AuthorityCode.SUB_WORK_TYPE_MANAGE)
    @PostMapping
    public ResponseEntity<ApiResponse<SubWorkTypeResponse>> createSubWorkType(
            @Valid @RequestBody SubWorkTypeSaveRequest request) {
        SubWorkTypeResponse response = subWorkTypeService.createSubWorkType(request);
        URI location = URI.create("/v1/sub-work-types/" + response.subWorkTypeId());
        return ResponseEntity.created(location).body(ApiResponse.created(response));
    }

    /*
     * 부분 수정이 아니라 폼 전체 저장이다. 생략한 값은 지워진다.
     * 사용 여부는 여기서 바뀌지 않는다 — 목록의 토글이 아래 엔드포인트로 따로 바꾼다.
     */
    @Operation(
            summary = "하위 업무 유형 수정",
            description = "수정 폼의 값으로 통째로 덮는다. 바뀐 승인 규칙은 이미 등록된 하위 업무에 소급되지 않는다.")
    @RequireAuthority(AuthorityCode.SUB_WORK_TYPE_MANAGE)
    @PatchMapping("/{subWorkTypeId}")
    public ApiResponse<SubWorkTypeResponse> updateSubWorkType(
            @PathVariable Long subWorkTypeId, @Valid @RequestBody SubWorkTypeSaveRequest request) {
        return ApiResponse.success(subWorkTypeService.updateSubWorkType(subWorkTypeId, request));
    }

    @Operation(
            summary = "하위 업무 유형 사용 여부 전환",
            description =
                    "목록의 '사용' 토글. 비활성 유형은 새 하위 업무가 고를 수 없을 뿐, " + "이미 그 유형으로 등록된 하위 업무는 그대로 남는다.")
    @RequireAuthority(AuthorityCode.SUB_WORK_TYPE_MANAGE)
    @PatchMapping("/{subWorkTypeId}/activation")
    public ApiResponse<SubWorkTypeResponse> changeActivation(
            @PathVariable Long subWorkTypeId,
            @Valid @RequestBody SubWorkTypeActivationRequest request) {
        return ApiResponse.success(subWorkTypeService.changeActivation(subWorkTypeId, request));
    }
}
