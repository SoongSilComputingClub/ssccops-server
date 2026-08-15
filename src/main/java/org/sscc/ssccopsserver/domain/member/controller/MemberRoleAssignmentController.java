package org.sscc.ssccopsserver.domain.member.controller;

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
import org.sscc.ssccopsserver.domain.member.dto.MemberRoleAssignRequest;
import org.sscc.ssccopsserver.domain.member.dto.MemberRoleAssignmentResponse;
import org.sscc.ssccopsserver.domain.member.dto.MemberRoleUpdateRequest;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.service.MemberRoleAssignmentService;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.authorization.RequireAuthority;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 회원 역할 부여·종료 API (#81 · ssccops#22).
 *
 * **요구 권한은 MEMBER_MANAGE가 아니라 ROLE_MANAGE다.** 표준코드가 두 권한의 범위를 "회원 정보·
 * 등급·상태의 조회와 변경"과 "역할에 권한 부여·회수"로 갈라 놓았고, 역할 부여는 그 사람이
 * 무엇을 할 수 있는지를 바꾸는 조작이라 인가 쪽이다. 회원 정보를 고칠 수 있다고 스스로에게
 * 임원 역할을 붙일 수 있으면 MEMBER_MANAGE가 사실상 최고 권한이 된다.
 *
 * 클래스 전체에 건다 (VR-M12) — RoleController·RoleAuthorityController와 같이 **조회도 예외가
 * 아니다.** 누가 어떤 역할을 맡고 있는지는 곧 누가 무엇을 할 수 있는지이며, 회원 관리 화면의
 * 다른 조회(MEMBER_MANAGE)와 요구 권한이 다르므로 컨트롤러를 MemberController와 나눈다.
 *
 * 경로가 /v1/members/{memberId}로 시작하지만 MemberController와 겹치지 않는다 — 저쪽은
 * /v1/members·/v1/members/{memberId}뿐이고 이쪽은 세그먼트가 하나 더 깊다
 * (RoleController ↔ RoleAuthorityController와 같은 배치).
 *
 * **변경은 즉시 반영된다** (BR-M31). 인가 판정이 요청마다 mbr_role_rel을 보므로 대상 회원은
 * 재로그인이나 토큰 재발급 없이 다음 요청부터 달라진다 — 세션을 무효화하는 절차가 여기에
 * 없는 것은 필요가 없어서다.
 */
@RestController
@RequestMapping("/v1/members/{memberId}/roles")
@RequireAuthority(AuthorityCode.ROLE_MANAGE)
@RequiredArgsConstructor
public class MemberRoleAssignmentController {

    private final MemberRoleAssignmentService memberRoleAssignmentService;

    /*
     * 목록. page 봉투를 싣지 않는다 (AP-11) — 한 사람이 맡는 역할은 지난 임기를 다 합쳐도
     * 수십 건 규모이고, 화면은 이력을 한 번에 받아 그 자리에서 걸러 그린다.
     */
    @Operation(
            summary = "회원 역할 배정 목록",
            description =
                    "회원의 역할 배정을 시작일 내림차순으로 조회한다. current=true면 지금 유효한 배정만"
                            + " 내리며(역할 시작일 <= 오늘 <= 종료일, 종료일 NULL이면 무기한), 생략하거나"
                            + " false면 종료된 배정까지 전부 내린다 — 종료는 삭제가 아니므로 지난 임기도"
                            + " 목록에 남는다. 행마다 실리는 current가 그중 지금 유효한 것을 가리킨다.")
    @GetMapping
    public ApiResponse<List<MemberRoleAssignmentResponse>> getAssignments(
            @PathVariable Long memberId,
            @RequestParam(defaultValue = "false") boolean current,
            @CurrentMember MemberEntity requester) {

        return ApiResponse.success(memberRoleAssignmentService.getAssignments(memberId, current));
    }

    /*
     * 부여. 요청자를 서비스로 넘기지 않는 것은 이 조작이 어떤 회원의 권한도 좁히지 못해
     * 자기 잠금 방지가 설 자리가 없기 때문이다 (MemberRoleAssignmentServiceImpl 주석 참고).
     */
    @Operation(
            summary = "회원에게 역할 부여",
            description =
                    "회원에게 역할을 부여한다. roleBgngYmd를 생략하면 오늘이고 rprsRoleYn을 생략하면"
                            + " false다. 종료일은 받지 않으며 부여는 언제나 무기한으로 시작한다."
                            + " 같은 역할이 기간을 겹쳐 이미 부여돼 있으면 409 ROLE_ALREADY_ASSIGNED이며,"
                            + " 기간이 겹치지 않는 재임은 허용한다. 대표로 지정하면 그 회원의 기존 대표"
                            + " 역할이 같은 트랜잭션에서 내려간다. 없는 회원은 404 NOT_FOUND, 없는 역할은"
                            + " 404 ROLE_NOT_FOUND다. 부여는 재로그인 없이 다음 요청부터 반영된다.")
    @PostMapping
    public ResponseEntity<ApiResponse<MemberRoleAssignmentResponse>> assign(
            @PathVariable Long memberId,
            @Valid @RequestBody MemberRoleAssignRequest request,
            @CurrentMember MemberEntity registrant) {

        MemberRoleAssignmentResponse response =
                memberRoleAssignmentService.assign(memberId, request);
        return ResponseEntity.created(
                        URI.create("/v1/members/" + memberId + "/roles/" + response.mbrRoleId()))
                .body(ApiResponse.created(response));
    }

    /*
     * 종료·대표 지정. **DELETE가 없는 것은 의도된 것이다** — 배정을 지우면 "언제까지 국장이었는가"가
     * 사라진다. 임기를 끝내는 길은 role_end_ymd를 채우는 이 PATCH 하나뿐이다.
     *
     * 요청자를 서비스로 넘기는 것은 자기 잠금 방지(VR-M13) 때문이다. 이 값이 없으면 마지막
     * ROLE_MANAGE 보유자가 자기 역할을 스스로 끝내 아무도 되돌릴 수 없는 상태를 만들 수 있다.
     */
    @Operation(
            summary = "회원 역할 배정 수정",
            description =
                    "역할 종료일과 대표 역할 여부를 바꾼다. null인 필드는 건드리지 않으며 시작일은 바꿀 수"
                            + " 없다. 종료일을 채우는 것이 임기를 끝내는 유일한 길이고 행을 지우지 않는다."
                            + " 종료일이 시작일보다 이르면 400 VALIDATION_FAILED, 다른 회원의 배정이거나"
                            + " 없는 배정이면 404다. 요청자 자신이 이 조작으로 ROLE_MANAGE를 잃게 되면"
                            + " 409 CANNOT_REVOKE_OWN_ROLE_MANAGE로 거절하며, 다른 사람이 끝내는 것은"
                            + " 막지 않는다. 회수도 재로그인 없이 다음 요청부터 반영된다.")
    @PatchMapping("/{mbrRoleId}")
    public ApiResponse<MemberRoleAssignmentResponse> updateAssignment(
            @PathVariable Long memberId,
            @PathVariable Long mbrRoleId,
            @Valid @RequestBody MemberRoleUpdateRequest request,
            @CurrentMember MemberEntity performer) {

        return ApiResponse.success(
                memberRoleAssignmentService.updateAssignment(
                        memberId, mbrRoleId, request, performer.getId()));
    }
}
