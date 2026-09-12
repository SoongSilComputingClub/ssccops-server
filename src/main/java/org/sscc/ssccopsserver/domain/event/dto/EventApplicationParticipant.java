package org.sscc.ssccopsserver.domain.event.dto;

import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;
import org.sscc.ssccopsserver.domain.event.repository.EventApplicationParticipation;

/*
 * 신청 한 건이 명단에 오른 자리 (#378 · ssccops#307). EventApplicationResponse.participant의 값이며
 * **명단에 없으면 이 record가 아니라 null이다** — 서버가 "미등록" 같은 대체값을 만들지 않는다
 * (RecruitmentApplicationResponse·MyApplicationResponse와 같은 태도).
 *
 * 명단 행 전체(EventParticipantResponse — 회원·등록자·일시)를 싣지 않는다. 신청 목록이 답할
 * 것은 "이미 올렸는가, 올렸다면 어떤 상태인가"뿐이고, 회원 정보는 옆의 application.member가
 * 이미 들고 있다.
 *
 * 취소(CANCELLED)도 그대로 CANCELLED다 — 취소는 기록이고(D16) "다시 올릴 수 있는가"는 등록
 * API의 409(EVENT_PARTICIPANT_DUPLICATED)가 답한다.
 */
public record EventApplicationParticipant(Long eventPtcpId, EventParticipantStatus ptcpSttsCd) {

    public static EventApplicationParticipant from(EventApplicationParticipation participation) {
        return new EventApplicationParticipant(
                participation.getEventPtcpId(), participation.getPtcpSttsCd());
    }
}
