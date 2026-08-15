package org.sscc.ssccopsserver.domain.operation.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.operation.dto.OperationHubResponse;
import org.sscc.ssccopsserver.domain.operation.service.OperationService;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.authorization.RequireAuthority;

import lombok.RequiredArgsConstructor;

/*
 * 운영 통합 조회 API (OPS-001 · ssccops-web#63). 경로 버전 /v1을 쓰고 컨텍스트 경로에
 * /api를 두지 않는다 (AP-01).
 *
 * 인가는 WORK_MANAGE 권한이다 — 업무·하위 업무·대시보드와 같은 권한이며 정의서의
 * '국장 이상'을 옮긴 것이다(#9). 회의 배열이 함께 실리지만 별도 권한을 겹쳐 걸지 않는다 —
 * 대시보드(OPS-038)가 승인함 데이터를 WORK_MANAGE 하나로 실어 내리는 것과 같은 판단이고,
 * 시드에서 MEETING_MANAGE는 늘 WORK_MANAGE와 같은 묶음(OPERATOR)으로 부여된다.
 *
 * 응답에 보는 사람에 따라 달라지는 값이 없어 @CurrentMember를 받지 않는다.
 */
@RestController
@RequiredArgsConstructor
@RequireAuthority(AuthorityCode.WORK_MANAGE)
@RequestMapping("/v1/operations")
public class OperationController {

    private final OperationService operationService;

    @GetMapping
    public ApiResponse<OperationHubResponse> getOperationHub() {
        return ApiResponse.success(operationService.getOperationHub());
    }
}
