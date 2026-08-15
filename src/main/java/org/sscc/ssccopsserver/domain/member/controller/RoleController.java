package org.sscc.ssccopsserver.domain.member.controller;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.dto.RoleCreateRequest;
import org.sscc.ssccopsserver.domain.member.dto.RoleDetailResponse;
import org.sscc.ssccopsserver.domain.member.dto.RoleResponse;
import org.sscc.ssccopsserver.domain.member.dto.RoleUpdateRequest;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.service.RoleService;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.authorization.RequireAuthority;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 조직 역할 관리 API (#79 · ssccops#17).
 *
 * **클래스 전체에 @RequireAuthority(ROLE_MANAGE)를 건다** (VR-M12). 역할은 권한이 붙는 자리라
 * 역할을 만드는 것 자체가 인가 조작이며, AuthorityController·RoleAuthorityController와 같이
 * 조회도 예외가 아니다.
 *
 * 경로가 RoleAuthorityController(/v1/roles/{roleId}/authorities)와 같은 접두사를 쓰지만 겹치지
 * 않는다 — 이쪽은 /v1/roles와 /v1/roles/{roleId}뿐이고 저쪽은 세그먼트가 하나 더 깊다.
 * 역할↔권한 매핑을 여기로 옮기지 않는 것은 그쪽 규칙(펼침·자기 잠금 방지)이 전부 권한 도메인에
 * 있기 때문이다.
 */
@RestController
@RequestMapping("/v1/roles")
@RequireAuthority(AuthorityCode.ROLE_MANAGE)
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    /*
     * 역할 목록. page 봉투를 싣지 않는다 (AP-11) — 역할은 기준 데이터라 수십 건 규모이고
     * 관리 화면도 역할 부여 화면의 드롭다운도 전량을 한 번에 그린다.
     */
    @Operation(
            summary = "역할 목록 조회",
            description =
                    "역할 전체를 분류 순번 → 역할 순번 순으로 조회한다. roleClsfCd로 분류를 지정하면 그 분류만"
                            + " 걸러 낸다. memberCount는 지금 이 역할을 맡고 있는 회원 수이며(종료된 배정은"
                            + " 세지 않는다) 역할 수와 무관하게 집계 질의 한 번으로 계산한다.")
    @GetMapping
    public ApiResponse<List<RoleResponse>> getRoles(
            @RequestParam(required = false) String roleClsfCd,
            @CurrentMember MemberEntity requester) {

        return ApiResponse.success(roleService.getRoles(roleClsfCd));
    }

    @Operation(
            summary = "역할 단건 조회",
            description =
                    "역할 한 건과 현재 재임 중인 회원 목록을 함께 조회한다. members의 기준은 목록의"
                            + " memberCount와 같아 두 화면의 숫자가 갈리지 않는다.")
    @GetMapping("/{roleId}")
    public ApiResponse<RoleDetailResponse> getRole(
            @PathVariable Long roleId, @CurrentMember MemberEntity requester) {

        return ApiResponse.success(roleService.getRole(roleId));
    }

    @Operation(
            summary = "역할 생성",
            description =
                    "새 역할을 만든다. indctSeqno를 생략하면 같은 분류 안의 최대값 + 1로 채워진다."
                            + " 같은 이름의 역할이 이미 있으면 409 ROLE_NAME_DUPLICATED, 없는 분류면"
                            + " 404 ROLE_CLASSIFICATION_NOT_FOUND다. 갓 만든 역할에는 권한이 하나도 붙어"
                            + " 있지 않으므로 PUT /v1/roles/{roleId}/authorities로 따로 부여해야 한다.")
    @PostMapping
    public ResponseEntity<ApiResponse<RoleResponse>> createRole(
            @Valid @RequestBody RoleCreateRequest request, @CurrentMember MemberEntity registrant) {

        RoleResponse response = roleService.createRole(request);
        return ResponseEntity.created(URI.create("/v1/roles/" + response.roleId()))
                .body(ApiResponse.created(response));
    }

    /*
     * 부분 수정이다 — 본문에 없는(null인) 필드는 건드리지 않는다. AuthorityController의 PATCH가
     * 노드 한 벌 전체를 받는 것과 갈리는 이유는 RoleUpdateRequest 주석에 있다.
     */
    @Operation(
            summary = "역할 수정",
            description =
                    "역할명·분류·표시 순번을 바꾼다. null인 필드는 건드리지 않는다. 분류만 바꾸고 indctSeqno를"
                            + " 생략하면 순번은 새 분류 안의 최대값 + 1로 다시 매겨진다. 이름이 다른 역할과"
                            + " 겹치면 409 ROLE_NAME_DUPLICATED다.")
    @PatchMapping("/{roleId}")
    public ApiResponse<RoleResponse> updateRole(
            @PathVariable Long roleId,
            @Valid @RequestBody RoleUpdateRequest request,
            @CurrentMember MemberEntity performer) {

        return ApiResponse.success(roleService.updateRole(roleId, request));
    }

    /*
     * 삭제. 204가 아니라 data가 null인 200인 것은 모든 응답이 ApiResponse 봉투를 쓰기 때문이다
     * (AuthorityController.deleteAuthority와 같은 판단).
     */
    @Operation(
            summary = "역할 삭제",
            description =
                    "아무에게도 배정된 적이 없고(종료된 배정도 이력으로 본다) 권한도 붙어 있지 않을 때만"
                            + " 지워진다. 그 밖에는 409 ROLE_IN_USE이며, 권한부터 회수하거나 배정 이력이 있는"
                            + " 역할은 그대로 두어야 한다.")
    @DeleteMapping("/{roleId}")
    public ApiResponse<Void> deleteRole(
            @PathVariable Long roleId, @CurrentMember MemberEntity performer) {

        roleService.deleteRole(roleId);
        return ApiResponse.successWithNoData();
    }
}
