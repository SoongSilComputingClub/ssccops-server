package org.sscc.ssccopsserver.domain.operation.dto;

import jakarta.validation.constraints.NotNull;

import org.sscc.ssccopsserver.domain.operation.entity.AgendaProcessStatus;

/*
 * 안건 수정 요청 (OPS-028 · PATCH /v1/meetings/{id}/agendas/{agendaId}). 정의서 비고
 * "논의 내용·처리 구분"대로 바꿀 수 있는 필드만 받는다 — 연결 운영 건·제목·제출자는
 * 다시 상정하는 것과 다름없어 이 API의 범위 밖이다(MeetingAgendaEntity.update 참고).
 *
 * **전체 교체**다 — content·resultContent를 생략하면 지운 것으로 본다(SubWorkUpdateRequest와
 * 같은 판단). processStatus는 화면이 칩 중 하나를 항상 골라 두므로 필수로 받는다.
 */
public record MeetingAgendaUpdateRequest(
        String content, String resultContent, @NotNull AgendaProcessStatus processStatus) {}
