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
 *
 * 그 대신 쓰는 쪽에 **`@ParameterObject`가 붙는다**. springdoc은 이 저장소의 `@ModelAttribute`
 * 조건 record를 펼치지 않고 «`condition`이라는 필수 쿼리 파라미터 하나»로 낸다(기존 스펙이 전부
 * 그 모양이다). 그러면 이 엔드포인트에 **새 필수 파라미터가 생긴 것**이 되어 OpenAPI 하위 호환
 * 게이트(ADR-0032)가 막는다 — 실제로 한 번 막혔다. `@ParameterObject`는 문서에만 영향을 주고
 * 바인딩은 그대로여서, 스펙에는 선택 파라미터 `app` 하나가 더해질 뿐이다.
 *
 * **목록 쪽(`NotificationListCondition`)에는 붙이지 않았다** — 그쪽 `condition`은 이미 발행된
 * 스펙에 있어 지금 펼치면 «있던 파라미터가 사라진다»가 되고, 그 정리는 이 저장소의 조건 record
 * 전부에 걸린 별개의 작업이다. 목록의 `app`은 `@Operation` 설명이 문장으로 든다.
 */
public record NotificationAppCondition(NotificationApp app) {}
