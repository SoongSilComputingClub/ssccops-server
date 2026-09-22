package org.sscc.ssccopsserver.domain.notification.controller;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.notification.dto.PushConfigResponse;
import org.sscc.ssccopsserver.domain.notification.dto.PushSubscriptionRequest;
import org.sscc.ssccopsserver.domain.notification.dto.PushSubscriptionResponse;
import org.sscc.ssccopsserver.domain.notification.dto.PushUnsubscribeRequest;
import org.sscc.ssccopsserver.domain.notification.service.PushSubscriptionService;
import org.sscc.ssccopsserver.global.apipayload.ApiResponse;
import org.sscc.ssccopsserver.global.security.resolver.CurrentMember;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

/*
 * 푸시 구독 API (ssccops#446 · ADR-0045). 계약은 #446 «API 계약» 그대로다.
 *
 * **`@RequireAuthority`를 걸지 않고 인증만 요구한다** — 자기 구독만 다루는 자원이라 자격의 근거가
 * 권한이 아니라 «로그인한 본인»이다(회차 착지 `AcademicSessionController`와 같은 판단). 미가입은
 * `@CurrentMember`가 403 SIGNUP_REQUIRED로 끊는다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/push")
public class PushSubscriptionController {

    private final PushSubscriptionService pushSubscriptionService;

    @Operation(
            summary = "푸시 설정 조회",
            description =
                    "브라우저가 PushManager.subscribe의 applicationServerKey로 넣을 VAPID 공개키"
                            + "(base64url). **publicKey가 null이면 이 서버는 푸시를 보내지 않는다**"
                            + "(키 미설정 또는 ssccops.push.enabled=false) — 그때 구독 토글을 그리지 않는다.")
    @GetMapping("/config")
    public ApiResponse<PushConfigResponse> getPushConfig() {
        return ApiResponse.success(pushSubscriptionService.config());
    }

    @Operation(
            summary = "푸시 구독 등록",
            description =
                    "PushSubscription.toJSON()의 endpoint·keys{p256dh, auth}·expirationTime에 app"
                            + "(ADMIN·LMS·WWW)을 더해 보낸다. **같은 endpoint는 갱신이다** — 새로 만들면 201,"
                            + " 이미 있어 키·회원을 갱신했으면 200. 한 기기를 두 사람이 번갈아 쓰면 마지막"
                            + " 등록한 사람의 구독이 된다.")
    @PostMapping("/subscriptions")
    public ResponseEntity<ApiResponse<PushSubscriptionResponse>> subscribe(
            @Valid @RequestBody PushSubscriptionRequest request,
            @CurrentMember MemberEntity member) {
        PushSubscriptionService.SubscribeResult result =
                pushSubscriptionService.subscribe(member, request);
        PushSubscriptionResponse body = new PushSubscriptionResponse(result.subscriptionId());
        return result.created()
                ? ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(body))
                : ResponseEntity.ok(ApiResponse.success(body));
    }

    @Operation(
            summary = "푸시 구독 해지",
            description =
                    "endpoint로 자기 구독을 지운다. **언제나 204다** — 없는 endpoint·남의 구독은 아무 일도"
                            + " 하지 않는다(로그아웃 경로에서 부르므로 실패로 보일 이유가 없고, 남의 구독을"
                            + " 지우는 길은 없다).")
    @DeleteMapping("/subscriptions")
    public ResponseEntity<Void> unsubscribe(
            @Valid @RequestBody PushUnsubscribeRequest request,
            @CurrentMember MemberEntity member) {
        pushSubscriptionService.unsubscribe(member.getId(), request.endpoint());
        return ResponseEntity.noContent().build();
    }
}
