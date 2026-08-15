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
 * 인가는 WORK_MANAGE 권한이다 — WorkController·SubWorkController와 같은 권한이며 정의서의
 * '국장 이상'을 옮긴 것이다(#9). 대시보드가 그 둘의 조회를 요약한 화면이라 별도 권한을
 * 새로 만들지 않는다.
 */
@RestController
@RequiredArgsConstructor
@RequireAuthority(AuthorityCode.WORK_MANAGE)
@RequestMapping("/v1/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping
    public ApiResponse<DashboardResponse> getDashboard(@CurrentMember MemberEntity viewer) {
        return ApiResponse.success(dashboardService.getDashboard(viewer));
    }
}
