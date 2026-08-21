package org.sscc.ssccopsserver.domain.operation.controller;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.WorkCreateResponse;
import org.sscc.ssccopsserver.domain.operation.dto.WorkDetailResponse;
import org.sscc.ssccopsserver.domain.operation.dto.WorkListItemResponse;
import org.sscc.ssccopsserver.domain.operation.dto.WorkSearchCondition;
import org.sscc.ssccopsserver.domain.operation.dto.WorkSearchResponse;
import org.sscc.ssccopsserver.domain.operation.dto.WorkUpdateRequest;
import org.sscc.ssccopsserver.domain.operation.service.WorkService;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.authorization.RequireAuthority;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import lombok.RequiredArgsConstructor;

/*
 * 업무 API (OPS-002). 경로 버전 /v1을 쓰고 컨텍스트 경로에 /api를 두지 않는다 (AP-01).
 *
 * 인가는 메서드마다 갈린다(#101) — 조회는 WORK_READ, 쓰기는 WORK_MANAGE다. 정의서의
 * '국장 이상' 전체 허용은 WORK_MANAGE 그대로이고(국장은 OPERATOR를 통해·회장·부회장·총무는
 * EXECUTIVE를 통해 닿는다), 국원은 WORK_READ만 받아 조회는 되지만 생성·수정은 막힌다.
 * WORK_READ가 WORK_MANAGE의 자식이라 WORK_MANAGE 보유자는 별도 매핑 없이 조회도 통과한다.
 * 클래스 레벨에 걸지 않는 것은 SubWorkTypeController와 같은 이유다 — 메서드마다 다른 권한을
 * 요구하면 클래스 레벨 하나로는 표현할 수 없다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/works")
public class WorkController {

    private final WorkService workService;

    @RequireAuthority(AuthorityCode.WORK_MANAGE)
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
    @RequireAuthority(AuthorityCode.WORK_READ)
    @GetMapping("/{workId}")
    public ApiResponse<WorkDetailResponse> getWork(@PathVariable Long workId) {
        return ApiResponse.success(workService.getWork(workId));
    }

    /*
     * 업무 기본 정보 수정 (OPS-004). '업무 상세' 화면의 '수정' 액션이 부른다.
     *
     * 본문은 등록(OPS-002)과 같은 필드 구성이며 **전체 교체**다 — review 같은 선택 입력도
     * 생략하면 지운 것으로 본다(WorkUpdateRequest 주석). workStatus는 요청 DTO에 아예 없어
     * 이 경로로 바꿀 수 없다(POL-003) — 상태는 전이 액션 엔드포인트(OPS-005)만의 몫이다.
     *
     * 응답이 상세 조회와 같은 WorkDetailResponse인 것은 화면이 수정 직후 재조회 없이 같은
     * 화면을 그대로 갱신할 수 있어야 하기 때문이다(다른 PATCH 엔드포인트들과 같은 판단).
     */
    @RequireAuthority(AuthorityCode.WORK_MANAGE)
    @PatchMapping("/{workId}")
    public ApiResponse<WorkDetailResponse> updateWork(
            @PathVariable Long workId, @Valid @RequestBody WorkUpdateRequest request) {
        return ApiResponse.success(workService.updateWork(workId, request));
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
    @RequireAuthority(AuthorityCode.WORK_READ)
    @GetMapping
    public ApiResponse<List<WorkListItemResponse>> searchWorks(
            @Valid @ModelAttribute WorkSearchCondition condition) {
        WorkSearchResponse result = workService.searchWorks(condition);
        return ApiResponse.success(result.works(), result.page());
    }

    /*
     * 업무 삭제(#125). work 자기 자신과 그 아래 살아있는 sub-work 전체를 함께 소프트
     * 삭제한다(계단식). 소유권(담당자 본인 여부)은 보지 않고 WORK_DELETE 보유 여부만으로
     * 판정한다 — 다른 하위 업무 쓰기 작업이 쓰는 소유권 계층(SubWorkOwnershipPolicy)과
     * 다른 결정이다. 204가 아니라 data가 null인 200인 것은 다른 삭제 엔드포인트와 같은
     * ApiResponse 컨벤션이다.
     */
    @RequireAuthority(AuthorityCode.WORK_DELETE)
    @DeleteMapping("/{workId}")
    public ApiResponse<Void> deleteWork(@PathVariable Long workId) {
        workService.deleteWork(workId);
        return ApiResponse.successWithNoData();
    }
}
