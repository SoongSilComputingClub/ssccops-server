package org.sscc.ssccopsserver.domain.operation.controller;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.operation.dto.ApprovalInboxItemResponse;
import org.sscc.ssccopsserver.domain.operation.dto.ApprovalInboxResponse;
import org.sscc.ssccopsserver.domain.operation.dto.ApprovalInboxSearchCondition;
import org.sscc.ssccopsserver.domain.operation.service.ApprovalService;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.authorization.RequireAuthority;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import lombok.RequiredArgsConstructor;

/*
 * 승인함 API (OPS-017 · #47). 하위 업무가 아니라 '승인해야 할 것들'이 리소스라 경로가
 * /v1/sub-works 아래가 아니다 — 대시보드(OPS-038)도 같은 목록을 요약해 쓴다.
 *
 * 승인·반려와 투표는 여기 있지 않다. 승인·반려는 상태 전이(POST /v1/sub-works/{id}/transitions)이고
 * 투표는 하위 업무에 달린 하위 리소스(POST /v1/sub-works/{id}/approvals/votes)라, 둘 다
 * 대상 하위 업무를 경로에 갖는다. 이 컨트롤러는 조회만 맡는다.
 *
 * 인가는 WORK_MANAGE다(#101). 원래는 권한 인가(#9)를 걸지 않은 유일한 운영 컨트롤러였는데
 * — '누가 승인할 수 있는가'가 정의서상 권한 코드가 아니라 '승인자 본인'(ApprovalAuthorityPolicy)이라
 * 그 판정은 여전히 유효하다 — 그와 별개로 '승인함 화면 자체를 볼 수 있는가'는 국원을 뺀
 * 운영진 전체로 좁혀야 해서(#101) 새로 걸었다. 목록을 승인자별로 좁히지 않는 것은 화면이
 * WORK_MANAGE 보유 운영진 전체에게 같은 승인함을 보여주기 때문이며, 실제 승인·반려는 전이
 * API(@RequireAuthority(WORK_READ) + 승인자 판정)가 건별로 걸러낸다.
 */
@RestController
@RequiredArgsConstructor
@RequireAuthority(AuthorityCode.WORK_MANAGE)
@RequestMapping("/v1/approvals")
public class ApprovalController {

    private final ApprovalService approvalService;

    /*
     * 승인함 조회. 화면 진입과 탭(대기·승인·반려) 전환이 모두 이 하나를 부른다.
     * 목록이므로 응답은 data 배열과 page 봉투 두 갈래다 (AP-11).
     */
    @GetMapping
    public ApiResponse<List<ApprovalInboxItemResponse>> searchApprovals(
            @Valid @ModelAttribute ApprovalInboxSearchCondition condition,
            @CurrentMember MemberEntity viewer) {
        ApprovalInboxResponse result = approvalService.searchApprovals(condition, viewer);
        return ApiResponse.success(result.approvals(), result.page());
    }
}
