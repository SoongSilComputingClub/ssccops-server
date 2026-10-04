package org.sscc.ssccopsserver.domain.operation.dto;

/*
 * 드래프트 안건 → 하위 업무 승격 응답 (#644 · ssccops#580 · POST
 * /v1/meetings/{id}/agendas/{agendaId}/promote-sub-work).
 *
 * 업무 승격 응답(MeetingAgendaPromoteResponse)과 같은 이유로 안건과 새 하위 업무를 함께 싣는다 —
 * 화면은 «만든 하위 업무 열기»에 subWorkId가, 회의 상세의 안건 항목 갱신에 안건이 필요하다.
 * 업무 승격 응답에 subWork 칸을 더해 한 모양으로 합치지 않은 것은 결정이다: 한쪽 칸이 늘 null인
 * 응답은 클라이언트가 어느 쪽인지 다시 가려야 하고, 기존 /promote 계약도 바뀐다.
 */
public record MeetingAgendaSubWorkPromoteResponse(
        MeetingAgendaResponse agenda, SubWorkCreateResponse subWork) {}
