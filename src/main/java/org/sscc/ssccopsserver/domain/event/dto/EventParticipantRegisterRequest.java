package org.sscc.ssccopsserver.domain.event.dto;

import jakarta.validation.constraints.NotNull;

import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;

/*
 * 참가자 등록 요청 (ssccops#146 · POST /v1/events/{eventId}/participants).
 *
 * 근거가 둘이다 — **응답 기반**(formRspnsId, 폼으로 신청한 사람을 심사 뒤 올린다)과
 * **수동**(mbrId, 전화·현장 접수처럼 폼을 거치지 않은 사람). 둘은 상호 배타이며 어기면
 * 400 INVALID_PARTICIPANT_SOURCE다.
 *
 * 한쪽을 조용히 우선하지 않는 것은 그 선택이 event_ptcp.form_rspns_id를 남길지 말지를 가르기
 * 때문이다 — 그 값은 나중에 "이 사람이 왜 명단에 있는가"를 답하는 유일한 근거라 추측으로
 * 정할 수 없다. 상호 배타를 @AssertTrue로 DTO에 넣지 않은 것은 실패가 VALIDATION_FAILED로
 * 뭉개져 웹이 안내 문구를 고를 수 없기 때문이다(#141 REVIEW_OPINION_REQUIRED와 같은 판단).
 *
 * **등록자(rgtr_mbr_id)는 여기에 없다** — 인증 주체에서 서버가 채운다. 받아 주면 "누가
 * 올렸는가"를 스스로 적어 넣을 수 있어 기록이 증거가 되지 못한다 (#78이 세운 규칙).
 *
 * ptcpSttsCd는 CONFIRMED·WAITLISTED만 받는다(EventParticipantStatus.isRegistrable) —
 * 취소로 시작하는 등록은 없다.
 */
public record EventParticipantRegisterRequest(
        Long formRspnsId, Long mbrId, @NotNull EventParticipantStatus ptcpSttsCd) {

    /** 근거가 정확히 하나인가. 판단만 하고 거절은 서비스가 한다 */
    public boolean hasExactlyOneSource() {
        return (formRspnsId == null) != (mbrId == null);
    }

    public boolean isResponseBased() {
        return formRspnsId != null;
    }
}
