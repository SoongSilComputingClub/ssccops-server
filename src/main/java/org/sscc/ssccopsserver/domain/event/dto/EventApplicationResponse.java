package org.sscc.ssccopsserver.domain.event.dto;

import org.sscc.ssccopsserver.domain.form.dto.FormResponseSummaryResponse;

/*
 * 행사 신청 목록 항목 (#378 · ssccops#307 · GET /v1/events/{eventId}/applications).
 *
 * 폼 응답 요약(application)에 **명단 등록 여부(participant)**를 나란히 실은 모양이다. 그전까지는
 * FormResponseSummaryResponse를 그대로 내렸는데, 웹이 "이미 명단에 있다"를 그리려면 명단을
 * 따로 받아 회원으로 맞춰야 했고 그 판정이 화면마다 다르게 적혔다 — 서버가 한 번 답한다.
 *
 * **폼 도메인의 FormResponseSummaryResponse에 얹지 않는다.** event_ptcp는 폼이 모르는 개념이고
 * 그 필드는 폼 응답 목록·내 응답 목록·검토 처리 응답에서 언제나 null이 되어 어느 화면에서
 * 뜻이 있는 값인지를 DTO가 말해 주지 못한다(RecruitmentApplicationResponse와 같은 판단).
 *
 * **필드를 옮겨 적지 않고 감싼다** — 모집 신청자 목록(RecruitmentApplicationResponse)이 요약의
 * 여섯 필드를 복사하는 것과 갈리는 지점이다. 그쪽은 계약이 먼저 굳어 바꾸지 못한 것이고,
 * 이쪽은 새로 여는 계약이라 폼 요약에 필드가 늘어도(#143 rspnsSeq · #196 responseTitle) 여기와
 * 짝을 맞추는 테스트가 필요 없는 모양을 고른다.
 *
 * participant는 명단에 없으면 null이다 — EventApplicationParticipant 참고.
 */
public record EventApplicationResponse(
        FormResponseSummaryResponse application, EventApplicationParticipant participant) {}
