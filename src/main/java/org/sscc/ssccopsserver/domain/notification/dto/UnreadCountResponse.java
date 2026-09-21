package org.sscc.ssccopsserver.domain.notification.dto;

/** GET /v1/notifications/unread-count — 종 아이콘 배지 하나를 위한 값 (ssccops#446) */
public record UnreadCountResponse(long unreadCount) {}
