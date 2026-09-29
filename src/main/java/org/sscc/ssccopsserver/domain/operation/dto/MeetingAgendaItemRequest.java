package org.sscc.ssccopsserver.domain.operation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.sscc.ssccopsserver.domain.operation.entity.AgendaProcessStatus;

/*
 * 안건 한 건의 입력 (OPS-024 등록 시 agendas[] · OPS-027 상정 POST). 등록 화면(회의)이 안건을
 * 배열로 함께 보내는 것과 안건 상정 화면이 한 건씩 보내는 것이 같은 모양을 쓴다.
 *
 * **안건은 언제나 운영 건을 가리킨다**(#593 · ADR-0055) — 제목은 그 운영 건의 oper_ttl이고 안건이
 * 따로 제목을 갖지 않는다. 그전에는 agendaName과 targetOperationId가 상호 배타였는데(@AssertTrue),
 * 독립 안건을 만들 길이 화면에 없었고 실제 데이터도 전부 연결형이었다.
 *
 * 정의서의 submitterId는 클라이언트가 지정하지 않는다 — 등록·상정 API를 호출한 인증 주체로 서버가
 * 고정한다(LY-05 준용, 다른 등록자류 필드와 같은 판단).
 */
public record MeetingAgendaItemRequest(
        @NotNull @Positive Long targetOperationId,
        AgendaProcessStatus processStatus,
        String content) {}
