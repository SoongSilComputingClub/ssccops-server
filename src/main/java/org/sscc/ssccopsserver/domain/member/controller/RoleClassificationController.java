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
import org.sscc.ssccopsserver.domain.member.dto.RoleClassificationCreateRequest;
import org.sscc.ssccopsserver.domain.member.dto.RoleClassificationResponse;
import org.sscc.ssccopsserver.domain.member.dto.RoleClassificationUpdateRequest;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.member.service.RoleClassificationService;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.authorization.RequireAuthority;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 역할 분류 관리 API (#80 · ssccops#23).
 *
 * **인가가 핸들러마다 갈린다** — 권한 관리(AuthorityController)가 클래스 전체를 ROLE_MANAGE로
 * 잠그는 것과 다르다. 저쪽은 어떤 묶음 권한이 있는지 자체가 운영 구조를 드러내고 그 트리를
 * 읽는 화면이 권한 관리 하나뿐이지만, 역할 분류는 역할 목록의 필터 칩이 쓰는 값이라 조회를
 * 막으면 역할을 볼 수 있는 사람이 분류로 거르지 못한다. 분류 이름(직책·부서·프로젝트 …)
 * 자체는 조직 구조를 드러내지 않는다. 폼 라벨(#34)의 목록 조회가 권한을 요구하지 않는 것과
 * 같은 판단이다.
 *
 * 조회 핸들러도 @CurrentMember를 받되 값을 쓰지 않는다. 시큐리티 설정은 토큰 유효성(401)까지만
 * 보고 가입 여부는 보지 않으므로, 이 파라미터가 미가입 주체를 403 SIGNUP_REQUIRED로 끊는
 * 자리다. role_clsf에는 등록자·변경자 컬럼이 없어 값 자체는 저장하지 않는다.
 */
@RestController
@RequestMapping("/v1/role-classifications")
@RequiredArgsConstructor
public class RoleClassificationController {

    private final RoleClassificationService roleClassificationService;

    /*
     * 분류 목록. 목록이지만 page 봉투를 싣지 않는다 (AP-11) — 운영진이 손으로 만드는 데이터라
     * 수십 건을 넘지 않고 화면도 전체를 한 번에 그리므로 페이징할 축이 없다.
     */
    @Operation(
            summary = "역할 분류 목록 조회",
            description =
                    "역할 분류 전체를 indctSeqno 오름차순으로 조회한다(동률은 코드순). 각 분류의 roleCount는"
                            + " 그 분류에 속한 역할 수이며, 0이 아니면 삭제가 409"
                            + " ROLE_CLASSIFICATION_IN_USE로 거절된다. 역할 목록의 필터가 쓰는 값이라"
                            + " 조회에는 권한이 필요 없다.")
    @GetMapping
    public ApiResponse<List<RoleClassificationResponse>> getClassifications(
            @CurrentMember MemberEntity requester) {
        return ApiResponse.success(roleClassificationService.getClassifications());
    }

    @Operation(
            summary = "역할 분류 생성",
            description =
                    "새 역할 분류를 만든다. roleClsfCd는 요청이 정하며 ^[A-Z][A-Z0-9_]{1,19}$ 형식이어야"
                            + " 한다(어기면 400 VALIDATION_FAILED). 같은 코드가 이미 있으면 409"
                            + " ROLE_CLASSIFICATION_CODE_DUPLICATED다. 새 분류는 데이터사전의 표준코드"
                            + " 시트에도 등재해야 한다.")
    @RequireAuthority(AuthorityCode.ROLE_MANAGE)
    @PostMapping
    public ResponseEntity<ApiResponse<RoleClassificationResponse>> createClassification(
            @Valid @RequestBody RoleClassificationCreateRequest request,
            @CurrentMember MemberEntity registrant) {

        RoleClassificationResponse response =
                roleClassificationService.createClassification(request);
        return ResponseEntity.created(
                        URI.create("/v1/role-classifications/" + response.roleClsfCd()))
                .body(ApiResponse.created(response));
    }

    @Operation(
            summary = "역할 분류 수정",
            description =
                    "이름(roleClsfNm)과 표시 순번(indctSeqno)을 바꾼다. indctSeqno를 생략하면 현재 값을"
                            + " 유지한다. roleClsfCd는 PK이자 role이 FK로 가리키는 값이라 본문에 없으며"
                            + " 바꿀 수 없다 — 새로 만들고 역할을 옮긴 뒤 기존 것을 지우는 것이 경로다."
                            + " SYSTEM 분류는 이름을 바꿀 수 없다(409"
                            + " SYSTEM_ROLE_CLASSIFICATION_IMMUTABLE). 시드된 5종의 이름 변경은 허용된다.")
    @RequireAuthority(AuthorityCode.ROLE_MANAGE)
    @PatchMapping("/{roleClsfCd}")
    public ApiResponse<RoleClassificationResponse> updateClassification(
            @PathVariable String roleClsfCd,
            @Valid @RequestBody RoleClassificationUpdateRequest request,
            @CurrentMember MemberEntity performer) {

        return ApiResponse.success(
                roleClassificationService.updateClassification(roleClsfCd, request));
    }

    /*
     * 삭제. 204가 아니라 data가 null인 200인 것은 모든 응답이 ApiResponse 봉투를 쓰기 때문이다 —
     * 이 하나만 본문이 없으면 웹의 공통 응답 처리가 예외를 하나 더 갖게 된다 (#36·#65와 같은 판단).
     */
    @Operation(
            summary = "역할 분류 삭제",
            description =
                    "소속 역할이 하나도 없을 때만 지워진다. 소속 역할이 있으면 409"
                            + " ROLE_CLASSIFICATION_IN_USE이며(역할을 다른 분류로 먼저 옮겨야 한다),"
                            + " SYSTEM 분류는 409 SYSTEM_ROLE_CLASSIFICATION_IMMUTABLE이다.")
    @RequireAuthority(AuthorityCode.ROLE_MANAGE)
    @DeleteMapping("/{roleClsfCd}")
    public ApiResponse<Void> deleteClassification(
            @PathVariable String roleClsfCd, @CurrentMember MemberEntity performer) {

        roleClassificationService.deleteClassification(roleClsfCd);
        return ApiResponse.successWithNoData();
    }
}
