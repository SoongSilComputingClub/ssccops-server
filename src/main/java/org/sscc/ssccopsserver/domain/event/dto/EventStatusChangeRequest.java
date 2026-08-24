package org.sscc.ssccopsserver.domain.event.dto;

import jakarta.validation.constraints.NotNull;

import org.sscc.ssccopsserver.domain.event.code.EventStatusAction;

/*
 * 행사 게시 상태 전이 요청 (ssccops#139 · POST /v1/events/{eventId}/status).
 *
 * 다음 상태(eventSttsCd)가 아니라 액션을 받는다 — 다음 상태를 받으면 클라이언트가 전이표를
 * 들고 있어야 하고, 표가 바뀔 때 웹과 서버가 따로 바뀌어 어긋난다 (FormStatusChangeRequest
 * 선례). 어느 상태로 가는지는 EventStatusAction이 정한다.
 *
 * 전이 가능 여부를 Bean Validation으로 잡지 않는 것도 같은 이유다 — 현재 상태를 알아야
 * 판단할 수 있고, 실패가 VALIDATION_FAILED로 바뀌면 계약표의
 * INVALID_EVENT_STATUS_TRANSITION과 어긋난다.
 */
public record EventStatusChangeRequest(@NotNull EventStatusAction action) {}
