package org.sscc.ssccopsserver.domain.academicprogram.dto;

import jakarta.validation.constraints.NotNull;

import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;

/*
 * 팀원 상태 변경 요청 (#612 · PATCH /v1/academic-programs/{academicProgramId}/members/{eventPtcpId}).
 *
 * 다음 상태를 받는다 — 행사 참가자 API(EventParticipantStatusChangeRequest)와 같은 모양이다. 갈 수
 * 있는 길은 넷이다:
 *
 *   WAITLISTED → CONFIRMED   승격
 *   CONFIRMED  → WAITLISTED  강등
 *   CONFIRMED  → CANCELLED   제외 (행은 지우지 않는다 — 지난 출석이 가리킨다)
 *   CANCELLED  → CONFIRMED   재합류 (학술 경로만 · EventParticipantEntity.rejoin)
 *
 * 그 밖은 400 INVALID_PARTICIPANT_STATUS_TRANSITION이다(행사 도메인의 전이표가 답한다).
 */
public record AcademicProgramMemberStatusChangeRequest(
        @NotNull EventParticipantStatus ptcpSttsCd) {}
