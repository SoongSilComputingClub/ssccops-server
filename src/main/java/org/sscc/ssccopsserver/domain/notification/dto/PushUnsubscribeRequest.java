package org.sscc.ssccopsserver.domain.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** DELETE /v1/push/subscriptions 의 본문 — endpoint가 구독의 정체성이다 */
public record PushUnsubscribeRequest(@NotBlank @Size(max = 2000) String endpoint) {}
