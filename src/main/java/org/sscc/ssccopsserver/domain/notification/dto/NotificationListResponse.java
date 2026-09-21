package org.sscc.ssccopsserver.domain.notification.dto;

import java.util.List;

/*
 * GET /v1/notifications 의 응답 본문 (ssccops#446 계약 `{ items, nextCursor, unreadCount }`).
 *
 * 다른 목록이 쓰는 `ApiResponse.success(data, page)` 봉투(AP-11)가 아니라 계약이 정한 모양이다 —
 * 종 아이콘의 배지가 목록과 같은 응답에서 `unreadCount`를 받아야 하는데 `PageResponse`에는
 * 그 자리가 없고, 웹(#447)이 이 모양으로 먼저 간다. `nextCursor`가 null이면 마지막 페이지다.
 */
public record NotificationListResponse(
        List<NotificationResponse> items, String nextCursor, long unreadCount) {}
