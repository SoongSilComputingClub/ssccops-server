package org.sscc.ssccopsserver.domain.event.repository;

import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;

/*
 * 내 신청 조회(ssccops#145)가 쓰는 "이 회원이 이 행사 명단에 있는가" 한 줄 (EventParticipantCount
 * 선례).
 *
 * 엔티티를 그대로 받지 않는 것은 필요한 것이 세 값뿐이기 때문이다 — 회원·등록자·근거 응답까지
 * 딸려 오면 명단 한 줄마다 조회가 더 나가거나(DB-13) 그것을 막기 위해 쓰지도 않을 연관을
 * @EntityGraph에 적어야 한다.
 */
public interface MyApplicationParticipation {

    Long getEventId();

    Long getEventPtcpId();

    EventParticipantStatus getPtcpSttsCd();
}
