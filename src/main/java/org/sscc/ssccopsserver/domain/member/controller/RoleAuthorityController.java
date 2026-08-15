package org.sscc.ssccopsserver.domain.member.controller;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.dto.RoleAuthorityReplaceRequest;
import org.sscc.ssccopsserver.domain.member.dto.RoleAuthorityResponse;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.service.AuthorityAdminService;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.authorization.RequireAuthority;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 역할의 권한 부여·회수 API (#65 · ssccops#70 BR-M30).
 *
 * 경로는 역할의 하위 자원이지만 컨트롤러를 역할 쪽이 아니라 여기에 둔 것은 규칙이 전부 권한
 * 쪽에 있기 때문이다(펼침·자기 잠금 방지). 폼 라벨 지정 교체를 FormController가 아니라
 * FormLabelController가 맡는 것과 같은 판단이다.
 *
 * 클래스 전체에 @RequireAuthority(ROLE_MANAGE)를 건다 (VR-M12).
 *
 * **부여·회수는 즉시 반영된다** (BR-M31). 인가 판정이 요청마다 role_authrt_rel을 보므로
 * 대상 역할을 가진 회원은 재로그인이나 토큰 재발급 없이 다음 요청부터 달라진다 — 세션을
 * 무효화하는 절차가 여기에 없는 것은 필요가 없어서다.
 */
@RestController
@RequestMapping("/v1/roles/{roleId}/authorities")
@RequireAuthority(AuthorityCode.ROLE_MANAGE)
@RequiredArgsConstructor
public class RoleAuthorityController {

    private final AuthorityAdminService authorityAdminService;

    @Operation(
            summary = "역할의 권한 조회",
            description =
                    "역할에 직접 부여된 권한(grants)과 자손까지 펼친 결과(effectiveAuthrtCds)를 함께 내려준다."
                            + " 체크박스 트리는 grants로 체크 상태를, effectiveAuthrtCds로 '상위 부여로 함께"
                            + " 열린' 표시를 그린다.")
    @GetMapping
    public ApiResponse<RoleAuthorityResponse> getRoleAuthorities(
            @PathVariable Long roleId, @CurrentMember MemberEntity requester) {
        return ApiResponse.success(authorityAdminService.getRoleAuthorities(roleId));
    }

    /*
     * 전체 교체이므로 생성이 아니라 200이다. 요청에 없는 권한은 회수되고 빈 배열이면 전부 회수된다.
     *
     * 요청자를 서비스로 넘기는 것은 자기 잠금 방지(VR-M13) 때문이다 — 이 값이 없으면 "누가
     * 회수했는가"를 알 수 없어 마지막 ROLE_MANAGE 보유자가 스스로를 잠그는 것을 막을 수 없다.
     */
    @Operation(
            summary = "역할의 권한 전체 교체",
            description =
                    "역할에 부여된 권한을 요청받은 목록으로 통째로 교체한다. 요청에 없는 권한은 회수되고 빈"
                            + " 배열이면 전부 회수된다. 유지되는 부여는 부여 시각(crtDt)이 보존된다. 요청자"
                            + " 자신이 ROLE_MANAGE를 잃게 되는 교체는 409 CANNOT_REVOKE_OWN_ROLE_MANAGE로"
                            + " 거절한다. 변경은 재로그인 없이 다음 요청부터 반영된다.")
    @PutMapping
    public ApiResponse<RoleAuthorityResponse> replaceRoleAuthorities(
            @PathVariable Long roleId,
            @Valid @RequestBody RoleAuthorityReplaceRequest request,
            @CurrentMember MemberEntity requester) {

        return ApiResponse.success(
                authorityAdminService.replaceRoleAuthorities(
                        roleId, request.authrtCds(), requester.getId()));
    }
}
