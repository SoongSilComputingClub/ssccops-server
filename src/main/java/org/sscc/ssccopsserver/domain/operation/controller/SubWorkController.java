package org.sscc.ssccopsserver.domain.operation.controller;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkChecklistItemUpdateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkChecklistItemUpdateResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkCreateResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkDetailResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkSearchCondition;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkSearchResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkSummaryResponse;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkTransitionRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkTransitionResponse;
import org.sscc.ssccopsserver.domain.operation.service.SubWorkService;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

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
            @CurrentMember MemberEntity registrant) {
        SubWorkCreateResponse response = subWorkService.createSubWork(request, registrant);
        URI location = URI.create("/v1/sub-works/" + response.subWorkId());
        return ResponseEntity.created(location).body(ApiResponse.created(response));
    }

    /*
     * 하위 업무 목록 조회 (OPS-008). '운영 통합 › 하위 업무' 화면이 진입할 때와 필터 칩을
     * 누를 때마다 호출한다. 상위 업무를 가로지르는 목록이라 상위 업무 상세(OPS-003)의
     * 하위 업무 목록과는 다른 리소스다.
     *
     * 조건은 개별 @RequestParam으로 늘어놓지 않고 record 하나로 받는다 — 필터가 늘 때마다
     * 시그니처가 자라는 것을 막는다. 값 해석(기준 코드·커서)은 DTO와 서비스가 맡으므로
     * 여기서 분기하지 않는다 (LY-02).
     *
     * 목록이므로 응답은 data 배열과 page 봉투 두 갈래다 (AP-11).
     */
    @GetMapping
    public ApiResponse<List<SubWorkSummaryResponse>> searchSubWorks(
            @Valid @ModelAttribute SubWorkSearchCondition condition) {
        SubWorkSearchResponse result = subWorkService.searchSubWorks(condition);
        return ApiResponse.success(result.subWorks(), result.page());
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
            @CurrentMember MemberEntity performer) {
        return ApiResponse.success(subWorkService.transitionSubWork(subWorkId, request, performer));
    }

    /*
     * 완료 체크리스트 항목 체크·해제 (OPS-013). 상세 화면(OPS-SCR-002)의 체크박스가 부른다.
     *
     * 상태를 PATCH로 쓰지 않는다는 POL-003·AP-03과 어긋나지 않는다 — 여기서 바꾸는 것은
     * 업무 상태가 아니라 체크리스트 항목 자신의 완료 여부이며, 부분 수정이므로 PATCH다 (AP-06).
     * 체크가 완료 승인으로 이어지는지는 전이 엔드포인트가 따로 판단한다.
     *
     * 항목의 소속·상태 제약은 서비스와 도메인이 판단하므로 여기서 분기하지 않는다 (LY-02).
     */
    @PatchMapping("/{subWorkId}/checklist/{checklistItemId}")
    public ApiResponse<SubWorkChecklistItemUpdateResponse> updateChecklistItem(
            @PathVariable Long subWorkId,
            @PathVariable Long checklistItemId,
            @Valid @RequestBody SubWorkChecklistItemUpdateRequest request,
            @CurrentMember MemberEntity performer) {
        return ApiResponse.success(
                subWorkService.updateChecklistItem(subWorkId, checklistItemId, request, performer));
    }
}
