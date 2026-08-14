package org.sscc.ssccopsserver.domain.operation.controller;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateResponse;
import org.sscc.ssccopsserver.domain.operation.dto.WorkDetailResponse;
import org.sscc.ssccopsserver.domain.operation.dto.WorkListItemResponse;
import org.sscc.ssccopsserver.domain.operation.dto.WorkSearchCondition;
import org.sscc.ssccopsserver.domain.operation.dto.WorkSearchResponse;
import org.sscc.ssccopsserver.domain.operation.service.WorkService;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import lombok.RequiredArgsConstructor;

/*
 * 업무 API (OPS-002). 경로 버전 /v1을 쓰고 컨텍스트 경로에 /api를 두지 않는다 (AP-01).
 *
 * 정의서상 권한은 '국장 이상'이나 역할 인가가 아직 구현되지 않아 현재는 인증만 요구한다.
 * 인증 주체에 GrantedAuthority가 부여되지 않아 hasRole 계열이 항상 실패하기 때문이며,
 * 역할 인가가 AOP로 붙을 때 이 엔드포인트에 함께 적용한다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/works")
public class WorkController {

    private final WorkService workService;

    @PostMapping
    public ResponseEntity<ApiResponse<WorkCreateResponse>> create(
            @Valid @RequestBody WorkCreateRequest request, @CurrentMember MemberEntity registrant) {
        WorkCreateResponse response = workService.createWork(request, registrant);
        URI location = URI.create("/v1/works/" + response.workId());
        return ResponseEntity.created(location).body(ApiResponse.created(response));
    }

    /*
     * 업무 상세 조회 (OPS-003). '업무 상세' 화면이 진입 시 호출하며, 좌측 상세 카드와
     * 우측 하위 업무 목록을 이 한 번의 호출로 채운다.
     *
     * 경로 변수는 work_id다 — oper_id가 아니다. 소프트 삭제된 건은 서비스가 404로 막으므로
     * 여기서 분기하지 않는다 (LY-02).
     */
    @GetMapping("/{workId}")
    public ApiResponse<WorkDetailResponse> getWork(@PathVariable Long workId) {
        return ApiResponse.success(workService.getWork(workId));
    }

    /*
     * 상위 업무 목록 조회 (OPS-020). '운영 통합 › 업무' 화면이 진입할 때 호출해 카드 그리드를
     * 채운다. 정의서에 목록 API가 없어 결번을 새로 부여한 엔드포인트이며, 업무·회의를 섞는
     * 통합 조회(OPS-001)와는 다른 리소스다.
     *
     * 조건은 개별 @RequestParam으로 늘어놓지 않고 record 하나로 받는다 — 필터가 늘 때마다
     * 시그니처가 자라는 것을 막는다. 값 해석(기준 코드·커서)은 DTO와 서비스가 맡으므로
     * 여기서 분기하지 않는다 (LY-02).
     *
     * 목록이므로 응답은 data 배열과 page 봉투 두 갈래다 (AP-11).
     */
    @GetMapping
    public ApiResponse<List<WorkListItemResponse>> searchWorks(
            @Valid @ModelAttribute WorkSearchCondition condition) {
        WorkSearchResponse result = workService.searchWorks(condition);
        return ApiResponse.success(result.works(), result.page());
    }
}
