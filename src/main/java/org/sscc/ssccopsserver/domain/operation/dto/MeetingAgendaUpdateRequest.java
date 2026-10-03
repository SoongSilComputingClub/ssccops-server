package org.sscc.ssccopsserver.domain.operation.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.sscc.ssccopsserver.domain.operation.entity.AgendaProcessStatus;

/*
 * 안건 수정 요청 (OPS-028 · PATCH /v1/meetings/{id}/agendas/{agendaId}). 정의서 비고
 * "논의 내용·처리 구분"대로 바꿀 수 있는 필드를 받고, 드래프트 안건이면 제목(agendaName)도
 * 받는다(#625 · ADR-0059). 연결 운영 건·제출자는 다시 상정하는 것과 다름없어 이 API의 범위 밖이다
 * — 드래프트를 운영 건에 잇는 길은 승격(POST …/promote) 하나다(MeetingAgendaEntity.update 참고).
 *
 * **전체 교체**다 — content·resultContent를 생략하면 지운 것으로 본다(SubWorkUpdateRequest와
 * 같은 판단). processStatus는 화면이 칩 중 하나를 항상 골라 두므로 필수로 받는다.
 *
 * **agendaName만은 선택이고 생략하면 그대로 둔다.** 드래프트의 제목을 비울 수는 없고(그러면
 * «운영 건 또는 제목 중 하나»가 깨진다), 이 필드 이전부터 수정 요청을 보내던 화면은 그것을 싣지
 * 않는다 — 필수로 두면 그 요청이 전부 400이 된다. 운영 건을 가리키는 안건에 주면 400이다(그
 * 판정은 안건을 읽어야 해서 엔티티가 한다). 빈 문자열·공백은 «지우기»로 읽힐 수 있어 여기서 막는다.
 */
public record MeetingAgendaUpdateRequest(
        @Size(max = 100) String agendaName,
        String content,
        String resultContent,
        @NotNull AgendaProcessStatus processStatus) {

    @AssertTrue(message = "안건 제목은 비울 수 없습니다.")
    public boolean isAgendaNameBlankFree() {
        return agendaName == null || !agendaName.isBlank();
    }
}
