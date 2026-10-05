package org.sscc.ssccopsserver.domain.operation.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.sscc.ssccopsserver.domain.operation.entity.AgendaProcessStatus;

/*
 * 안건 한 건의 입력 (OPS-024 등록 시 agendas[] · OPS-027 상정 POST). 등록 화면(회의)이 안건을
 * 배열로 함께 보내는 것과 안건 상정 화면이 한 건씩 보내는 것이 같은 모양을 쓴다.
 *
 * **targetOperationId와 agendaName 중 정확히 하나**를 준다(#625 · ADR-0059). 운영 건을 주면 연결
 * 안건이고 제목은 그 운영 건의 oper_ttl이다. 제목만 주면 드래프트 안건이고, 나중에 «업무로
 * 만들기»(POST …/agendas/{agendaId}/promote)로 업무를 가리키게 된다. 둘 다 주거나 둘 다 없으면
 * 400이다 — 공백뿐인 제목은 없는 것으로 본다.
 *
 * 한때(ADR-0055 · #593) targetOperationId가 @NotNull이었다. 그 필수를 푼 것은 요청 필드의
 * 완화라 api-compat 게이트가 막지 않는다.
 *
 * 정의서의 submitterId는 클라이언트가 지정하지 않는다 — 등록·상정 API를 호출한 인증 주체로 서버가
 * 고정한다(LY-05 준용, 다른 등록자류 필드와 같은 판단).
 */
public record MeetingAgendaItemRequest(
        @Positive Long targetOperationId,
        @Size(max = 100) String agendaName,
        AgendaProcessStatus processStatus,
        String content) {

    @AssertTrue(message = "연결할 운영 건 또는 안건 제목 중 하나만 입력해야 합니다.")
    public boolean isExactlyOneTargetSpecified() {
        boolean hasOperation = targetOperationId != null;
        boolean hasName = agendaName != null && !agendaName.isBlank();
        return hasOperation ^ hasName;
    }
}
