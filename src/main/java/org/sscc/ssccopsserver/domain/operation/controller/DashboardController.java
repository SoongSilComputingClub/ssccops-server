package org.sscc.ssccopsserver.domain.operation.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.operation.dto.DashboardResponse;
import org.sscc.ssccopsserver.domain.operation.service.DashboardService;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.authorization.RequireAuthority;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import lombok.RequiredArgsConstructor;

/*
 * 운영 대시보드 API (OPS-038 · ssccops-web#60). 경로 버전 /v1을 쓰고 컨텍스트 경로에 /api를
 * 두지 않는다 (AP-01).
 *
 * 인가는 WORK_READ다(#101) — 국원도 자신의 담당 업무·다가오는 마감은 볼 수 있어야 한다.
 * WORK_MANAGE 보유자는 트리 펼침으로 WORK_READ를 자동으로 가지므로 국장 이상은 그대로
 * 통과한다. 다만 응답에 실리는 '승인 대기' 목록은 승인함(WORK_MANAGE)과 같은 데이터라 —
 * WORK_READ만 가진 조회자(국원)에게는 DashboardServiceImpl이 그 부분만 빈 배열로 돌려준다
 * (컨트롤러 레벨 권한 하나로는 응답 안의 서로 다른 두 영역을 나눠 가릴 수 없다).
 */
@RestController
@RequiredArgsConstructor
@RequireAuthority(AuthorityCode.WORK_READ)
@RequestMapping("/v1/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping
    public ApiResponse<DashboardResponse> getDashboard(@CurrentMember MemberEntity viewer) {
        return ApiResponse.success(dashboardService.getDashboard(viewer));
    }
}
