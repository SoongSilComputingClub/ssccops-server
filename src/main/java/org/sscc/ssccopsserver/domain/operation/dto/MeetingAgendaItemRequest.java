package org.sscc.ssccopsserver.domain.operation.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.sscc.ssccopsserver.domain.operation.entity.AgendaProcessStatus;

/*
 * 안건 한 건의 입력 (OPS-024 등록 시 agendas[] · OPS-027 상정 POST). 등록 화면(회의)이 안건을
 * 배열로 함께 보내는 것과 안건 상정 화면이 한 건씩 보내는 것이 같은 모양을 쓴다.
 *
 * targetOperationId·agendaName은 상호 배타적이다(OPS-027 "둘 중 하나 필수") — 운영 건에
 * 연결된 안건은 그 oper_ttl을 제목으로 쓰고, 독립 안건만 agendaName을 직접 받는다. 정의서의
 * submitterId는 클라이언트가 지정하지 않는다 — 등록·상정 API를 호출한 인증 주체로 서버가
 * 고정한다(LY-05 준용, 다른 등록자류 필드와 같은 판단).
 */
public record MeetingAgendaItemRequest(
        @Positive Long targetOperationId,
        @Size(max = 100) String agendaName,
        AgendaProcessStatus processStatus,
        String content) {

    @AssertTrue(message = "연결할 운영 건 또는 안건명 중 하나만 입력해야 합니다.")
    public boolean isExactlyOneTargetSpecified() {
        boolean hasOperation = targetOperationId != null;
        boolean hasName = agendaName != null && !agendaName.isBlank();
        return hasOperation ^ hasName;
    }
}
