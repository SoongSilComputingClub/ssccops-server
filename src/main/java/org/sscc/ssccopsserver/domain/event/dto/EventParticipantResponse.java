package org.sscc.ssccopsserver.domain.event.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.event.code.EventParticipantStatus;
import org.sscc.ssccopsserver.domain.event.entity.EventParticipantEntity;
import org.sscc.ssccopsserver.domain.form.dto.ResponseMemberSummary;

/*
 * 참가자 명단 항목 (ssccops#146 · GET /v1/events/{eventId}/participants).
 *
 * 회원 정보는 명단 행에 복사되어 있지 않고 mbr에서 조인해 온다 — 복사해 두면 회원이 학과를
 * 바꾸거나 상태가 졸업으로 넘어간 뒤에도 명단만 옛 값을 보여준다. 폼 응답 목록의
 * ResponseMemberSummary를 그대로 쓰는 것은 두 화면이 요구하는 컬럼(회원_명·학번·학과·등급·
 * 상태)이 같기 때문이다 — 같은 모양을 이름만 바꿔 한 벌 더 만들면 한쪽에 필드가 늘 때
 * 두 화면이 갈린다. 그 record가 폼 도메인에 있는 것은 먼저 필요해진 쪽이 거기였기 때문이고,
 * 행사 도메인은 이미 폼의 타입(FormReceiptStatus)을 참조하고 있다.
 *
 * formRspnsId는 **신청 근거**다 — 수동 등록(전화·현장 접수)이면 null이며, 그것이 "이 사람이
 * 왜 명단에 있는가"를 답하는 값이다.
 *
 * 등록자(rgtrMbrId)는 싣지 않는다. 감사용으로 저장하지만 명단 표가 그리는 값이 아니고,
 * 실으면 운영자 사이의 처리 내역이 참가자 목록에 섞인다 — 필요해지면 이력 조회로 연다.
 */
public record EventParticipantResponse(
        Long eventPtcpId,
        EventParticipantStatus ptcpSttsCd,
        Long formRspnsId,
        ResponseMemberSummary member,
        OffsetDateTime crtDt,
        OffsetDateTime mdfcnDt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static EventParticipantResponse from(EventParticipantEntity participant) {
        return new EventParticipantResponse(
                participant.getId(),
                participant.getStatus(),
                participant.getFormResponse() == null
                        ? null
                        : participant.getFormResponse().getId(),
                ResponseMemberSummary.from(participant.getMember()),
                toOffsetDateTime(participant.getCreatedAt()),
                toOffsetDateTime(participant.getUpdatedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
