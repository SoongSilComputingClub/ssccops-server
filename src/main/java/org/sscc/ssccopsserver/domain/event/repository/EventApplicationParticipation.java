package org.sscc.ssccopsserver.domain.event.repository;

import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;

/*
 * 신청 목록(#378)이 쓰는 "이 응답이 명단에 올랐는가" 한 줄 (MyApplicationParticipation 선례).
 *
 * 엔티티를 그대로 받지 않는 것은 필요한 것이 세 값뿐이기 때문이다 — 회원·등록자·근거 응답이
 * 딸려 오면 명단 한 줄마다 조회가 더 나가거나(DB-13) 그것을 막기 위해 쓰지도 않을 연관을
 * @EntityGraph에 적어야 한다. 응답 쪽 정보는 옆의 폼 응답 요약이 이미 들고 있다.
 */
public interface EventApplicationParticipation {

    Long getFormRspnsId();

    Long getEventPtcpId();

    EventParticipantStatus getPtcpSttsCd();
}
