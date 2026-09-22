package org.sscc.ssccopsserver.domain.notification.dto;

import org.sscc.ssccopsserver.domain.notification.code.NotificationApp;

/*
 * `app` 하나뿐인 조회 조건 (#535 · ADR-0047). 배지(`GET /v1/notifications/unread-count`)가 쓴다.
 *
 * 값 하나에 record를 두는 것은 **거절의 모양을 목록과 같게 하기 위해서**다. `@RequestParam
 * NotificationApp app`으로 받으면 기준 코드에 없는 값이 `MethodArgumentTypeMismatchException`이
 * 되어 스프링 기본 `ProblemDetail`(`code` 필드가 없는 본문)로 나가고, 목록의 같은 실수는
 * `@ModelAttribute` 바인딩을 지나 `VALIDATION_FAILED` 봉투로 나간다 — 같은 오타가 두 엔드포인트에서
 * 다른 모양이면 화면이 두 갈래로 처리한다. 전역 핸들러에 타입 불일치 처리를 더하는 안은
 * 기각했다: 이 작업이 건드릴 범위를 넘어 모든 엔드포인트의 거절 본문이 바뀐다.
 */
public record NotificationAppCondition(NotificationApp app) {}
