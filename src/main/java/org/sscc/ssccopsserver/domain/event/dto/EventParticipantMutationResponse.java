package org.sscc.ssccopsserver.domain.event.dto;

import java.util.List;

import org.sscc.ssccopsserver.domain.event.entity.EventParticipantEntity;

/*
 * 참가자 등록·상태 전이의 응답 (ssccops#146 · POST · PATCH .../participants).
 *
 * 명단 항목 하나에 **정원 판단 재료**와 **경고**를 얹은 모양이다. 두 경로가 같은 응답을 쓰는
 * 것은 승격(WAITLISTED→CONFIRMED)도 등록과 똑같이 정원을 넘길 수 있기 때문이다 — 한쪽만
 * 숫자를 내리면 화면은 등록에서는 경고를 띄우고 승격에서는 띄우지 못한다.
 *
 * **정원 초과를 서버가 막지 않는다**(D5). ptcp_lmt_cnt는 참고치이고, 현장에서 자리를 더
 * 만들거나 노쇼를 예상해 초과 확정하는 것이 정상 운영이다. 대신 판단에 필요한 것을 전부
 * 내려 화면이 경고한다:
 *   confirmedCount   이 요청이 반영된 뒤의 확정 인원 (CONFIRMED만 센다)
 *   ptcpLmtCnt       정원. null이면 무제한이다
 *   capacityExceeded 정원이 있고 확정 인원이 그것을 넘었는가
 *
 * capacityExceeded를 서버가 계산해 내리는 것은 웹이 두 숫자로 다시 계산하면 '정원 null =
 * 무제한' 규칙이 두 벌이 되기 때문이다.
 */
public record EventParticipantMutationResponse(
        EventParticipantResponse participant,
        long confirmedCount,
        Integer ptcpLmtCnt,
        boolean capacityExceeded,
        List<EventParticipantWarningResponse> warnings) {

    public static EventParticipantMutationResponse of(
            EventParticipantEntity participant,
            long confirmedCount,
            Integer participantLimitCount,
            List<EventParticipantWarningResponse> warnings) {
        return new EventParticipantMutationResponse(
                EventParticipantResponse.from(participant),
                confirmedCount,
                participantLimitCount,
                participantLimitCount != null && confirmedCount > participantLimitCount,
                warnings);
    }
}
