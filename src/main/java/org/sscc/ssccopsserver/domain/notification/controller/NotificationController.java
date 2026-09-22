package org.sscc.ssccopsserver.domain.notification.controller;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.notification.dto.NotificationAppCondition;
import org.sscc.ssccopsserver.domain.notification.dto.NotificationListCondition;
import org.sscc.ssccopsserver.domain.notification.dto.NotificationListResponse;
import org.sscc.ssccopsserver.domain.notification.dto.NotificationReadAllResponse;
import org.sscc.ssccopsserver.domain.notification.dto.NotificationReadResponse;
import org.sscc.ssccopsserver.domain.notification.dto.TestNotificationRequest;
import org.sscc.ssccopsserver.domain.notification.dto.TestNotificationResponse;
import org.sscc.ssccopsserver.domain.notification.dto.UnreadCountResponse;
import org.sscc.ssccopsserver.domain.notification.service.NotificationService;
import org.sscc.ssccopsserver.domain.notification.service.TestNotificationService;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 알림 API (ssccops#446 · ADR-0045). 계약은 #446 «API 계약» 그대로다 — 웹(#447)이 이 모양으로 먼저 간다.
 *
 * **`@RequireAuthority`를 걸지 않고 인증만 요구한다** — 전부 «내 알림»이고 자격의 근거는 권한이
 * 아니라 로그인한 본인이다(`PushSubscriptionController`와 같다). 남의 알림은 403이 아니라 **404**다
 * (`NotificationErrorCode.NOTIFICATION_NOT_FOUND` 주석).
 *
 * 목록이 `ApiResponse.success(data, page)`(AP-11)가 아니라 `{items, nextCursor, unreadCount}`를
 * data로 싣는 이유는 `NotificationListResponse` 주석에 있다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;
    private final TestNotificationService testNotificationService;

    @Operation(
            summary = "내 알림 목록",
            description =
                    "최신부터 커서 페이징(AP-13). size 기본 20·최대 100, cursor는 직전 응답의 nextCursor"
                            + "(마지막 페이지면 null). unreadCount(안 읽은 수)가 함께 온다 — 종 아이콘"
                            + " 배지가 목록과 같은 응답에서 값을 받는다. 깨진 cursor는 400. "
                            + "app(ADMIN·LMS·WWW)을 주면 **그 앱이 수신 앱인 알림만** 온다(ADR-0047 —"
                            + " 기준표 noti_type_rcpn이 정하고, 등록된 행이 없는 유형은 그 알림 행 자신의"
                            + " app을 따른다). 함께 오는 unreadCount도 같은 필터를 지난다. **파라미터가"
                            + " 없으면 앱과 무관하게 전부**(«전체» 칩)이고, 기준 코드에 없는 값은 400.")
    @GetMapping
    public ApiResponse<NotificationListResponse> getNotifications(
            @Valid @ModelAttribute NotificationListCondition condition,
            @CurrentMember MemberEntity member) {
        return ApiResponse.success(
                notificationService.list(
                        member.getId(),
                        condition.sizeOrDefault(),
                        condition.cursor(),
                        condition.app()));
    }

    @Operation(
            summary = "안 읽은 알림 수",
            description =
                    "종 아이콘 배지 하나를 위한 값. 목록 없이 수만 필요할 때. app을 주면 목록과 **같은"
                            + " 필터**로 센다(#535 · ADR-0047), 없으면 전부.")
    @GetMapping("/unread-count")
    public ApiResponse<UnreadCountResponse> getUnreadCount(
            @Valid @ModelAttribute NotificationAppCondition condition,
            @CurrentMember MemberEntity member) {
        return ApiResponse.success(
                notificationService.unreadCount(member.getId(), condition.app()));
    }

    @Operation(
            summary = "알림 읽음",
            description =
                    "readAt을 지금으로. **이미 읽은 것은 그대로 200이고 처음 읽은 시각이 온다**(두 번 눌러도"
                            + " 오류가 아니다). 없는 알림과 남의 알림은 똑같이 404 NOT_FOUND.")
    @PostMapping("/{notificationId}/read")
    public ApiResponse<NotificationReadResponse> markRead(
            @PathVariable Long notificationId, @CurrentMember MemberEntity member) {
        return ApiResponse.success(notificationService.markRead(member.getId(), notificationId));
    }

    @Operation(
            summary = "모두 읽음",
            description = "내 안 읽은 알림 전부의 readAt을 지금으로. updated는 이번 호출이 바꾼 행 수(0이어도 200).")
    @PostMapping("/read-all")
    public ApiResponse<NotificationReadAllResponse> markAllRead(
            @CurrentMember MemberEntity member) {
        return ApiResponse.success(notificationService.markAllRead(member.getId()));
    }

    /*
     * 테스트 알림 (#528 · ssccops#454). 200이지 201이 아니다 — 만든 행보다 «몇 대에 갔나»가 답이고
     * Location으로 가리킬 단건 조회도 없다.
     */
    @Operation(
            summary = "테스트 알림 보내기",
            description =
                    "호출자 자신에게 TEST 알림 행 하나를 만들고 내 구독 전부로 푸시를 **동기로** 보낸다."
                            + " app(ADMIN·LMS·WWW)은 어느 앱의 «내 정보»에서 눌렀는가 — 행의 app과 링크"
                            + " 경로(ADMIN·LMS /my · WWW /me)가 그 값으로 정해진다. 응답의 pushed는 푸시"
                            + " 서비스가 받아 준 구독 수이며 발송기가 꺼진 환경에서는 0이다(행은 남는다)."
                            + " 회원당 1분 3회를 넘으면 429 RATE_LIMITED.")
    @PostMapping("/test")
    public ApiResponse<TestNotificationResponse> sendTest(
            @Valid @RequestBody TestNotificationRequest request,
            @CurrentMember MemberEntity member) {
        return ApiResponse.success(testNotificationService.send(member, request.app()));
    }
}
