package org.sscc.ssccopsserver.domain.notification.dto;

/** POST /v1/notifications/read-all — 이번 호출이 읽음으로 바꾼 행 수 */
public record NotificationReadAllResponse(int updated) {}
