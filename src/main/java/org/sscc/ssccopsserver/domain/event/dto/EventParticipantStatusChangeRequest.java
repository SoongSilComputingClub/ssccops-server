package org.sscc.ssccopsserver.domain.event.dto;

import jakarta.validation.constraints.NotNull;

import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;

/*
 * 참가 상태 변경 요청 (ssccops#146 · PATCH /v1/events/{eventId}/participants/{eventPtcpId}).
 *
 * 액션이 아니라 **상태**를 받는다. 행사·폼의 게시 전이가 액션을 받는 것과 갈리는 지점인데,
 * 그쪽은 '연다/닫는다'가 현재 상태에 따라 다른 결과로 가지만 여기서 운영자가 고르는 것은
 * 승격과 취소 둘뿐이고 각각 상태 하나와 1:1이라 액션 어휘가 상태 어휘를 그대로 베낀 것이
 * 된다 (#141 FormResponseReviewRequest와 같은 판단).
 *
 * 전이 가능 여부는 Bean Validation으로 잡지 않는다 — 현재 상태를 알아야 판단할 수 있고,
 * 실패가 VALIDATION_FAILED로 바뀌면 계약표의 INVALID_PARTICIPANT_STATUS_TRANSITION과
 * 어긋난다. 판단은 EventParticipantEntity.changeStatus가 갖는다.
 */
public record EventParticipantStatusChangeRequest(@NotNull EventParticipantStatus ptcpSttsCd) {}
