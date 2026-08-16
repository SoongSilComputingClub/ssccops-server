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
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.dto.AuthorityCreateRequest;
import org.sscc.ssccopsserver.domain.member.dto.AuthorityResponse;
import org.sscc.ssccopsserver.domain.member.dto.AuthorityTreeResponse;
import org.sscc.ssccopsserver.domain.member.dto.AuthorityUpdateRequest;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.service.AuthorityAdminService;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.authorization.RequireAuthority;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 권한 트리 관리 API (#65 · ssccops#70).
 *
 * **클래스 전체에 @RequireAuthority(ROLE_MANAGE)를 건다 (VR-M12).** 권한을 권한으로 지키는
 * 자리라 핸들러가 하나라도 빠지면 그 경로만으로 인가 전체를 고칠 수 있게 된다 — 조회도
 * 예외가 아니다. 어떤 묶음 권한이 있는지 자체가 운영 구조를 드러내고, 이 트리를 읽어야 하는
 * 화면은 권한 관리 화면 하나뿐이다. (버튼 노출용 권한 목록은 세션 응답의 capabilities가 준다.)
 */
@RestController
@RequestMapping("/v1/authorities")
@RequireAuthority(AuthorityCode.ROLE_MANAGE)
@RequiredArgsConstructor
public class AuthorityController {

    private final AuthorityAdminService authorityAdminService;

    /*
     * 권한 트리 전체. 최상위 노드의 배열이며 각 노드가 children으로 자손을 중첩해 갖는다.
     *
     * 목록이지만 page 봉투를 싣지 않는다 (AP-11) — 권한은 수십 건 규모이고 화면이 트리 전체를
     * 한 번에 그리므로 페이징할 축이 없다.
     */
    @Operation(
            summary = "권한 트리 조회",
            description =
                    "권한 전체를 계층 구조로 조회한다. 최상위 권한의 배열이며 각 노드의 children에 자손이 중첩된다."
                            + " sysYn = true는 코드가 직접 참조하는 권한이라 삭제·코드 변경이 막힌다.")
    @GetMapping
    public ApiResponse<List<AuthorityTreeResponse>> getAuthorityTree(
            @CurrentMember MemberEntity requester) {
        return ApiResponse.success(authorityAdminService.getAuthorityTree());
    }

    @Operation(
            summary = "사용자 정의 묶음 권한 생성",
            description =
                    "화면에서 만드는 권한은 언제나 sysYn = false다. upAuthrtCd를 지정하면 그 권한의 자식이 되고"
                            + " 생략하면 최상위가 된다. 같은 코드가 이미 있으면 409 AUTHORITY_CODE_DUPLICATED다.")
    @PostMapping
    public ResponseEntity<ApiResponse<AuthorityResponse>> createAuthority(
            @Valid @RequestBody AuthorityCreateRequest request,
            @CurrentMember MemberEntity registrant) {

        AuthorityResponse response = authorityAdminService.createAuthority(request);
        return ResponseEntity.created(URI.create("/v1/authorities/" + response.authrtCd()))
                .body(ApiResponse.created(response));
    }

    /*
     * 이름·설명·상위·표시 순번 변경. 메서드는 PATCH지만 본문은 노드 한 벌 전체다
     * (AuthorityUpdateRequest 주석 참고).
     */
    @Operation(
            summary = "권한 수정",
            description =
                    "이름·설명·상위·표시 순번을 바꾼다. 본문은 노드 한 벌 전체이며 upAuthrtCd를 생략하면 최상위로"
                            + " 올라간다. sysYn = true여도 이 값들은 바꿀 수 있지만 코드는 바꿀 수 없다"
                            + "(409 SYSTEM_AUTHORITY_IMMUTABLE). 자기 자신이나 자손을 상위로 지정하면"
                            + " 400 AUTHORITY_CYCLE_DETECTED다.")
    @PatchMapping("/{authrtCd}")
    public ApiResponse<AuthorityResponse> updateAuthority(
            @PathVariable String authrtCd,
            @Valid @RequestBody AuthorityUpdateRequest request,
            @CurrentMember MemberEntity performer) {

        return ApiResponse.success(authorityAdminService.updateAuthority(authrtCd, request));
    }

    /*
     * 삭제. 204가 아니라 data가 null인 200인 것은 모든 응답이 ApiResponse 봉투를 쓰기 때문이다 —
     * 이 하나만 본문이 없으면 웹의 공통 응답 처리가 예외를 하나 더 갖게 된다 (#36과 같은 판단).
     */
    @Operation(
            summary = "권한 삭제",
            description =
                    "sysYn = false이고 어느 역할에도 부여되지 않았으며 자식 권한이 없을 때만 지워진다."
                            + " 시스템 권한은 409 SYSTEM_AUTHORITY_IMMUTABLE, 부여됐거나 자식이 있으면"
                            + " 409 AUTHORITY_IN_USE다(먼저 회수·이동해야 한다).")
    @DeleteMapping("/{authrtCd}")
    public ApiResponse<Void> deleteAuthority(
            @PathVariable String authrtCd, @CurrentMember MemberEntity performer) {

        authorityAdminService.deleteAuthority(authrtCd);
        return ApiResponse.successWithNoData();
    }
}
