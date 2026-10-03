package org.sscc.ssccopsserver.domain.operation.dto;

/*
 * 드래프트 안건 승격 응답 (#625 · ADR-0059 · POST /v1/meetings/{id}/agendas/{agendaId}/promote).
 *
 * 안건과 새 업무를 함께 싣는다. 안건만 돌려주면 화면이 «만든 업무로 가기»에 필요한 workId를
 * 알 수 없다 — 안건이 싣는 targetOperation은 oper_id이고 업무 상세 경로는 work_id다. 업무만
 * 돌려주면 회의 상세의 안건 항목을 재조회 없이 갱신할 수 없다. 둘 다 이미 있는 응답 모양
 * (안건 상정·업무 등록)을 그대로 쓴다.
 */
public record MeetingAgendaPromoteResponse(MeetingAgendaResponse agenda, WorkCreateResponse work) {}
