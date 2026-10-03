package org.sscc.ssccopsserver.domain.operation.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.operation.dto.OperationHubResponse;
import org.sscc.ssccopsserver.domain.operation.dto.OperationTagCondition;
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
 *
 * tagId(#637)를 주면 세 배열 모두 그 태그가 달린 행만 남는다 — 각 행은 자기 운영 건의 태그를 본다
 * (하위 업무가 상위 업무의 태그를 물려받지 않는다). 트리의 상위 업무가 걸러져도 그 아래 하위 업무
 * 행은 자기 태그대로 남는다 — 화면이 트리를 그릴 때 부모 없는 행을 다룬다.
 */
@RestController
@RequiredArgsConstructor
@RequireAuthority(AuthorityCode.WORK_MANAGE)
@RequestMapping("/v1/operations")
public class OperationController {

    private final OperationService operationService;

    @GetMapping
    public ApiResponse<OperationHubResponse> getOperationHub(
            @ModelAttribute OperationTagCondition condition) {
        return ApiResponse.success(operationService.getOperationHub(condition.tagId()));
    }
}
