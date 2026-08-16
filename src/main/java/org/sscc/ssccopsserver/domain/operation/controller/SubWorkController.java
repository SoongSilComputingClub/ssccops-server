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
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
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
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkUpdateRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkVoteRequest;
import org.sscc.ssccopsserver.domain.operation.dto.SubWorkVoteResponse;
import org.sscc.ssccopsserver.domain.operation.service.SubWorkService;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.authorization.RequireAuthority;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import lombok.RequiredArgsConstructor;

/*
 * 하위 업무 API (OPS-007). 경로 버전 /v1을 쓰고 컨텍스트 경로에 /api를 두지 않는다 (AP-01).
 *
 * 인가는 메서드마다 갈린다(#101, WorkController와 같은 구조). 조회는 WORK_READ(WORK_MANAGE의
 * 자식이라 WORK_MANAGE 보유자는 별도 매핑 없이 통과한다), 생성은 WORK_MANAGE만(국원은 새
 * 하위 업무를 만들 수 없다 — 배정받은 건을 다루는 것과 새 건을 만드는 것은 다른 권한이다).
 * 수정·전이·체크리스트는 WORK_MANAGE 보유자거나 "본인이 담당자인 건"이어야 하는데,
 * @RequireAuthority는 레코드를 모르므로 여기서는 WORK_READ까지만 걸고 담당자 여부는
 * SubWorkOwnershipPolicy가 서비스 레이어에서 본다
 * (ApprovalAuthorityPolicy와 같은 층 — '무슨 일을 하는 사람인가'와 '이 건의 담당자
 * 본인인가'는 성질이 다르다). 승인·투표 자격도 마찬가지로 ApprovalAuthorityPolicy가 본다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/sub-works")
public class SubWorkController {

    private final SubWorkService subWorkService;

    @RequireAuthority(AuthorityCode.WORK_MANAGE)
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
    @RequireAuthority(AuthorityCode.WORK_READ)
    @GetMapping
    public ApiResponse<List<SubWorkSummaryResponse>> searchSubWorks(
            @Valid @ModelAttribute SubWorkSearchCondition condition) {
        SubWorkSearchResponse result = subWorkService.searchSubWorks(condition);
        return ApiResponse.success(result.subWorks(), result.page());
    }

    /*
     * 하위 업무 상세 조회 (OPS-009). '하위 업무 상세' 화면(OPS-SCR-002)이 진입 시 호출한다.
     * 소프트 삭제된 건은 서비스가 404로 막으므로 여기서 분기하지 않는다 (LY-02).
     *
     * 조회인데 인증 주체를 받는 것은 화면이 이 응답만으로 승인·반려 버튼을 그려야 하기 때문이다
     * (#58). 응답 자체는 누가 보든 같고, 보는 사람에 따라 갈리는 것은 canApprove·canReject와
     * myVote뿐이다. 판정을 프론트가 하지 않는 것은 서버의 승인자 판정과 어긋나면 버튼은 보이는데
     * 누르면 403이 나기 때문이다.
     */
    @RequireAuthority(AuthorityCode.WORK_READ)
    @GetMapping("/{subWorkId}")
    public ApiResponse<SubWorkDetailResponse> getSubWork(
            @PathVariable Long subWorkId, @CurrentMember MemberEntity viewer) {
        return ApiResponse.success(subWorkService.getSubWork(subWorkId, viewer));
    }

    /*
     * 하위 업무 기본 정보 수정 (OPS-030). 상세 화면(OPS-SCR-002)의 '수정' 액션이 부른다.
     *
     * 본문은 등록(OPS-007)과 같은 확장 속성 필드에서 workId·subWorkTypeId를 뺀 구성이며
     * **전체 교체**다 — content·completionCriteria·externalLink 같은 선택 입력도 생략하면
     * 지운 것으로 본다(SubWorkUpdateRequest 주석). workStatus는 요청 DTO에 아예 없어 이
     * 경로로 바꿀 수 없다(POL-003) — 상태는 전이 액션 엔드포인트(OPS-010)만의 몫이다.
     *
     * 응답이 상세 조회와 같은 SubWorkDetailResponse인 것도 같은 이유다 — canApprove·
     * canReject·quorum·myVote까지 함께 실려야 화면이 수정 직후 재조회 없이 그대로 갱신된다.
     */
    @RequireAuthority(AuthorityCode.WORK_READ)
    @PatchMapping("/{subWorkId}")
    public ApiResponse<SubWorkDetailResponse> updateSubWork(
            @PathVariable Long subWorkId,
            @Valid @RequestBody SubWorkUpdateRequest request,
            @CurrentMember MemberEntity viewer) {
        return ApiResponse.success(subWorkService.updateSubWork(subWorkId, request, viewer));
    }

    /*
     * 하위 업무 상태 전이 (OPS-010). 상세 화면(OPS-SCR-002)의 '반려'·'완료 승인' 버튼과
     * 담당자의 착수·검토요청이 모두 이 하나의 액션 경로를 쓴다. 상태를 PATCH로 직접 쓰는
     * 경로는 두지 않는다 (POL-003·AP-03).
     *
     * 전이 가능 여부·사유 필수 여부는 서비스와 도메인이 판단하므로 여기서 분기하지 않는다 (LY-02).
     * 상태 변경은 생성이 아니므로 200이다 (LY-06).
     */
    @RequireAuthority(AuthorityCode.WORK_READ)
    @PostMapping("/{subWorkId}/transitions")
    public ApiResponse<SubWorkTransitionResponse> transition(
            @PathVariable Long subWorkId,
            @Valid @RequestBody SubWorkTransitionRequest request,
            @CurrentMember MemberEntity performer) {
        return ApiResponse.success(subWorkService.transitionSubWork(subWorkId, request, performer));
    }

    /*
     * 정족수 승인 투표 (OPS-015 · #47). 승인함 화면의 카드에 있는 `찬성`·`반대` 버튼이 부른다.
     *
     * 승인·반려와 달리 별도 경로를 두는 것은 이것이 상태 전이가 아니기 때문이다 — 정족수를
     * 채워도 업무 상태·승인 상태는 그대로이고, 승인자가 전이 API를 눌러야 완료된다(POL-007).
     * 전이가 아니므로 POST /transitions에 액션을 하나 더 얹지 않았다.
     *
     * 투표 자격·정족수 유형 여부·상태 조건은 서비스와 도메인이 판단하므로 여기서 분기하지 않는다 (LY-02).
     * 표를 새로 만들든 기존 표를 바꾸든 결과가 같은 멱등한 호출이라 201이 아니라 200이다 (LY-06).
     */
    @RequireAuthority(AuthorityCode.WORK_READ)
    @PostMapping("/{subWorkId}/approvals/votes")
    public ApiResponse<SubWorkVoteResponse> vote(
            @PathVariable Long subWorkId,
            @Valid @RequestBody SubWorkVoteRequest request,
            @CurrentMember MemberEntity voter) {
        return ApiResponse.success(subWorkService.voteOnSubWork(subWorkId, request, voter));
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
    @RequireAuthority(AuthorityCode.WORK_READ)
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
