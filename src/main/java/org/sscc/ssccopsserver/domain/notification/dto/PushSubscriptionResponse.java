package org.sscc.ssccopsserver.domain.notification.dto;

/** POST /v1/push/subscriptions — 만들어졌거나(201) 갱신된(200) 구독의 식별자 */
public record PushSubscriptionResponse(Long subscriptionId) {}
