package org.sscc.ssccopsserver.domain.operation.controller;

import java.net.URI;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkCreateResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkDetailResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkTransitionRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkTransitionResponse;
import org.sscc.ssccopsserver.domain.operation.service.SubWorkService;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;

import lombok.RequiredArgsConstructor;

/*
 * 하위 업무 API (OPS-007). 경로 버전 /v1을 쓰고 컨텍스트 경로에 /api를 두지 않는다 (AP-01).
 *
 * 정의서상 권한은 '국장 이상'이나 역할 인가가 아직 구현되지 않아 현재는 인증만 요구한다.
 * 인증 주체에 GrantedAuthority가 부여되지 않아 hasRole 계열이 항상 실패하기 때문이며,
 * 역할 인가가 AOP로 붙을 때 이 엔드포인트에 함께 적용한다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/sub-works")
public class SubWorkController {

    private final SubWorkService subWorkService;

    @PostMapping
    public ResponseEntity<ApiResponse<SubWorkCreateResponse>> create(
            @Valid @RequestBody SubWorkCreateRequest request,
            @AuthenticationPrincipal MemberEntity registrant) {
        SubWorkCreateResponse response = subWorkService.createSubWork(request, registrant);
        URI location = URI.create("/v1/sub-works/" + response.subWorkId());
        return ResponseEntity.created(location).body(ApiResponse.created(response));
    }

    /*
     * 하위 업무 상세 조회 (OPS-009). '하위 업무 상세' 화면(OPS-SCR-002)이 진입 시 호출한다.
     * 소프트 삭제된 건은 서비스가 404로 막으므로 여기서 분기하지 않는다 (LY-02).
     */
    @GetMapping("/{subWorkId}")
    public ApiResponse<SubWorkDetailResponse> getSubWork(@PathVariable Long subWorkId) {
        return ApiResponse.success(subWorkService.getSubWork(subWorkId));
    }

    /*
     * 하위 업무 상태 전이 (OPS-010). 상세 화면(OPS-SCR-002)의 '반려'·'완료 승인' 버튼과
     * 담당자의 착수·검토요청이 모두 이 하나의 액션 경로를 쓴다. 상태를 PATCH로 직접 쓰는
     * 경로는 두지 않는다 (POL-003·AP-03).
     *
     * 전이 가능 여부·사유 필수 여부는 서비스와 도메인이 판단하므로 여기서 분기하지 않는다 (LY-02).
     * 상태 변경은 생성이 아니므로 200이다 (LY-06).
     */
    @PostMapping("/{subWorkId}/transitions")
    public ApiResponse<SubWorkTransitionResponse> transition(
            @PathVariable Long subWorkId,
            @Valid @RequestBody SubWorkTransitionRequest request,
            @AuthenticationPrincipal MemberEntity performer) {
        return ApiResponse.success(subWorkService.transitionSubWork(subWorkId, request, performer));
    }
}
