package org.sscc.ssccopsserver.domain.notification.dto;

import java.time.OffsetDateTime;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.sscc.ssccopsserver.domain.notification.code.NotificationApp;

/*
 * POST /v1/push/subscriptions 의 본문 (ssccops#446 계약).
 *
 * 브라우저 `PushSubscription.toJSON()`의 모양(endpoint · keys{p256dh, auth} · expirationTime)에
 * `app` 하나를 더한 것이다 — 서비스워커가 그 JSON을 그대로 싣고 어느 앱인지만 덧붙이면 된다.
 * 크기 상한은 컬럼 길이(endpoint 2000 · 키 255)와 같다.
 */
public record PushSubscriptionRequest(
        @NotBlank @Size(max = 2000) String endpoint,
        @NotNull @Valid Keys keys,
        OffsetDateTime expirationTime,
        @NotNull NotificationApp app) {

    public record Keys(
            @NotBlank @Size(max = 255) String p256dh, @NotBlank @Size(max = 255) String auth) {}
}
