package org.sscc.ssccopsserver.domain.notification.dto;

import jakarta.validation.constraints.NotNull;

import org.sscc.ssccopsserver.domain.notification.code.NotificationApp;

/*
 * POST /v1/notifications/test 본문 (#528 · ssccops#454). 어느 앱의 «내 정보»에서 눌렀는가 —
 * 알림 행의 app_cd와 링크 경로가 이 값으로 정해진다. 구독을 등록할 때와 같은 어휘다.
 */
public record TestNotificationRequest(@NotNull NotificationApp app) {}
