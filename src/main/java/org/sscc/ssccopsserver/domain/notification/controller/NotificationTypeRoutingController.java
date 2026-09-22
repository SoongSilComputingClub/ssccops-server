package org.sscc.ssccopsserver.domain.notification.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.member.code.AuthorityCode;
import org.sscc.ssccopsserver.domain.notification.dto.NotificationTypeRouteRequest;
import org.sscc.ssccopsserver.domain.notification.dto.NotificationTypeRouteResponse;
import org.sscc.ssccopsserver.domain.notification.service.NotificationTypeRoutingService;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.authorization.RequireAuthority;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 어드민 «설정 › 알림 유형» (#535 · ssccops#465 · ADR-0047).
 *
 * **`NotificationController`와 나눈 것은 자격의 근거가 다르기 때문이다.** 그쪽은 전부 «내
 * 알림»이라 인증만 요구하고 `@RequireAuthority`가 한 줄도 없다 — 그 클래스 주석이 그것을 규칙으로
 * 적어 두었다. 이쪽은 시스템 전체의 정책이라 반대다. 한 컨트롤러에 섞으면 «이 컨트롤러는 인증만»
 * 이라는 문장이 더 이상 참이 아니게 되고, 다음 사람이 새 핸들러에 권한을 빠뜨린다.
 *
 * **이 저장소에서 `@RequireAuthority(SUPER)`를 요구하는 첫 엔드포인트다.** `AuthorityCode.SUPER`의
 * 주석은 «어떤 엔드포인트도 SUPER를 요구하지 않는다»라고 적고 있었는데, 그것은 «SUPER를 판정의
 * 예외로 특별 취급하지 말라»는 규칙(AuthorityPolicy에 분기를 넣지 말 것)의 설명이었지 트리의
 * 최상위를 요구 권한으로 쓰지 말라는 뜻이 아니다. 여기에 맞는 자식 권한이 없다 —
 * `NOTIFICATION_ROUTE_MANAGE`를 새로 만들어 SUPER 직속에 매다는 안은 기각했다: 학기에 몇 번
 * 건드리는 시스템 정책이라 위임할 대상이 없고, 권한을 하나 늘리면 역할별 권한 화면에 영구히
 * 남는다. 넓힐 필요가 생기면 그때 권한을 만들고 이 한 줄을 바꾼다(ADR-0047이 권한을 SUPER로
 * 지목한 자리이기도 하다).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/notifications/types")
@RequireAuthority(AuthorityCode.SUPER)
public class NotificationTypeRoutingController {

    private final NotificationTypeRoutingService notificationTypeRoutingService;

    @Operation(
            summary = "알림 유형별 수신 앱 목록",
            description =
                    "알림 유형 전부와 그 유형이 보일 앱을 준다. 유형 목록은 코드가 아는 닫힌 집합이고"
                            + "(label은 화면에 그대로 쓰는 이름), apps는 기준표(noti_type_rcpn)가 정한"
                            + " 값이다. **followsSendingApp이 true면 기준표에 행이 없는 유형**이라 그"
                            + " 알림 행 자신의 app을 따르며 apps는 빈 배열이다(ADR-0047). 권한 SUPER.")
    @GetMapping
    public ApiResponse<List<NotificationTypeRouteResponse>> getTypes() {
        return ApiResponse.success(notificationTypeRoutingService.listTypes());
    }

    @Operation(
            summary = "알림 유형의 수신 앱 변경",
            description =
                    "그 유형의 수신 앱을 **통째로 교체**한다(체크박스 화면이 지금 켜진 것 전부를 보낸다)."
                            + " 빈 배열은 400 VALIDATION_FAILED — 알림을 조용히 끄는 설정을 만들 수 없게"
                            + " 한다(ADR-0047 «최소 한 앱»). 기준 코드에 없는 유형은 404 NOT_FOUND."
                            + " 바꾼 즉시 목록·배지·푸시에 반영되며 감사 로그"
                            + " notification.type.route가 남는다. 권한 SUPER.")
    @PutMapping("/{type}")
    public ApiResponse<NotificationTypeRouteResponse> replaceApps(
            @PathVariable String type, @RequestBody NotificationTypeRouteRequest request) {
        return ApiResponse.success(
                notificationTypeRoutingService.replaceApps(type, request.apps()));
    }
}
