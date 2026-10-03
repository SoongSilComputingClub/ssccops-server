package org.sscc.ssccopsserver.domain.operation.dto;

import org.sscc.ssccopsserver.domain.operation.entity.AgendaProcessStatus;
import org.sscc.ssccopsserver.domain.operation.entity.MeetingAgendaEntity;

/*
 * 안건 응답 (OPS-025 회의 상세의 agendas[] · OPS-027 안건 목록·상정 · OPS-028 안건 수정 ·
 * 승격). 네 API가 같은 모양을 쓴다 — 상정·수정 직후 화면이 재조회 없이 목록 항목을 그대로 갱신한다.
 *
 * 드래프트 안건(#625 · ADR-0059)은 targetOperation이 null이고 agendaName이 제목이다. 연결 안건은
 * 반대로 agendaName이 null이고 제목은 targetOperation.title이다. draft는 그 구별을 화면이 null
 * 검사로 추론하지 않게 따로 싣는 값이다(«드래프트» 배지 · «업무로 만들기» 버튼). 두 필드 모두
 * 더한 것이라 api-compat 게이트를 지난다.
 */
public record MeetingAgendaResponse(
        Long agendaId,
        Long meetingId,
        AgendaProcessStatus processStatus,
        Integer agendaOrder,
        AgendaTargetOperationResponse targetOperation,
        String content,
        String resultContent,
        MemberSummaryResponse submitter,
        String agendaName,
        boolean draft) {

    public static MeetingAgendaResponse from(MeetingAgendaEntity agenda) {
        return new MeetingAgendaResponse(
                agenda.getId(),
                agenda.getMeeting().getId(),
                agenda.getProcessStatus(),
                agenda.getAgendaOrder(),
                AgendaTargetOperationResponse.from(agenda.getOperation()),
                agenda.getContent(),
                agenda.getResultContent(),
                MemberSummaryResponse.from(agenda.getSubmitter()),
                agenda.getAgendaName(),
                agenda.isDraft());
    }
}
