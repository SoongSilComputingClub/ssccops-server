package org.sscc.ssccopsserver.domain.operation.dto;

import org.sscc.ssccopsserver.domain.operation.entity.AgendaProcessStatus;
import org.sscc.ssccopsserver.domain.operation.entity.MeetingAgendaEntity;

/*
 * 안건 응답 (OPS-025 회의 상세의 agendas[] · OPS-027 안건 목록·상정 · OPS-028 안건 수정).
 * 세 API가 같은 모양을 쓴다 — 상정·수정 직후 화면이 재조회 없이 목록 항목을 그대로 갱신한다.
 */
public record MeetingAgendaResponse(
        Long agendaId,
        Long meetingId,
        String agendaName,
        AgendaProcessStatus processStatus,
        Integer agendaOrder,
        AgendaTargetOperationResponse targetOperation,
        String content,
        String resultContent,
        MemberSummaryResponse submitter) {

    public static MeetingAgendaResponse from(MeetingAgendaEntity agenda) {
        return new MeetingAgendaResponse(
                agenda.getId(),
                agenda.getMeeting().getId(),
                agenda.getAgendaName(),
                agenda.getProcessStatus(),
                agenda.getAgendaOrder(),
                AgendaTargetOperationResponse.from(agenda.getOperation()),
                agenda.getContent(),
                agenda.getResultContent(),
                MemberSummaryResponse.from(agenda.getSubmitter()));
    }
}
