package org.sscc.ssccopsserver.domain.notification.dto;

/*
 * POST /v1/notifications/test 응답 (#528 · ssccops#454). pushed는 푸시 서비스가 받아 준 구독 수 —
 * 화면 문구 «보냈습니다 — 기기 알림을 확인하세요(n대)»의 n이다. 발송기가 꺼져 있으면 0.
 */
public record TestNotificationResponse(Long notificationId, int pushed) {}
