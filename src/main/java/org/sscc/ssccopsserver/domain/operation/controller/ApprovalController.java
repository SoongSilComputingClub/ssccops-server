package org.sscc.ssccopsserver.domain.operation.controller;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.operation.dto.ApprovalInboxItemResponse;
import org.sscc.ssccopsserver.domain.operation.dto.ApprovalInboxResponse;
import org.sscc.ssccopsserver.domain.operation.dto.ApprovalInboxSearchCondition;
import org.sscc.ssccopsserver.domain.operation.service.ApprovalService;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
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
 * 정의서상 권한은 '승인자 본인'이나 역할 인가가 아직 구현되지 않아 현재는 인증만 요구한다
 * (SubWorkController와 같은 선례). 목록을 승인자별로 좁히지 않는 것은 화면이 운영진 전체에게
 * 같은 승인함을 보여주기 때문이며, 실제 승인·반려는 전이 API가 승인자만 통과시킨다.
 */
@RestController
@RequiredArgsConstructor
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
