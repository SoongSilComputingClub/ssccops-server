package org.sscc.ssccopsserver.domain.academicprogram.dto;

import jakarta.validation.constraints.NotNull;

import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;

/*
 * 선발 한 줄 (#138 · POST .../recruitment/select의 selections 항목).
 *
 * 신청 근거는 폼 응답(formRspnsId)뿐이다 — 행사 참가자 등록(ssccops#146)이 수동 등록
 * ({mbrId})을 함께 받는 것과 갈리는데, 그쪽은 전화·현장 접수가 정상인 행사가 있고 여기는
 * 모집 폼을 통과한 신청자를 고르는 화면이라 근거 없는 팀원이 생길 자리가 없다. 명부 밖의
 * 사람을 넣어야 한다면 그것은 선발이 아니라 행사 참가자 등록 API의 일이다.
 *
 * ptcpSttsCd에 CONFIRMED·WAITLISTED만 온다는 검사를 여기서 하지 않는다. 등록으로 도달할 수
 * 있는 상태인지는 EventParticipantStatus.isRegistrable이 이미 아는 규칙이고, 실제로 막는 자리는
 * EventParticipantEntity.register다 — 여기에 한 벌 더 적으면 등록 경로가 늘 때 두 규칙이 갈린다.
 */
public record RecruitmentSelectionRequest(
        @NotNull Long formRspnsId, @NotNull EventParticipantStatus ptcpSttsCd) {}
