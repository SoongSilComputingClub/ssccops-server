package org.sscc.ssccopsserver.domain.event.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.event.code.EventPhase;
import org.sscc.ssccopsserver.domain.event.code.EventStatus;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.form.code.FormReceiptStatus;

/*
 * 행사 목록 항목 (ssccops#139 · GET /v1/events). 필드명은 웹과 합의된 계약이다.
 *
 * 본문(mtxtCn)이 없는 것이 이 DTO의 핵심이다 — md 본문은 10만 자까지 갈 수 있어 목록에 실으면
 * 응답이 행사 수만큼 곱해진다 (FormSummaryResponse가 qitemCpstCn을 빼는 것과 같은 판단).
 *
 * eventPhase는 DB 컬럼이 아니라 행사 일시에서 조회 시점에 파생한 값이다(D9 · EventPhasePolicy).
 * receiptStatus는 연결된 폼의 파생 값(FormReceiptPolicy)이며 폼이 없으면 null이다 — 모집
 * 중/마감을 행사에 저장하지 않는 것이 D3의 결정이라, 이 두 값 어느 쪽도 event 테이블에 없다.
 *
 * confirmedCount는 확정(CONFIRMED) 참가자만 센다. 화면의 "확정 N/정원(ptcpLmtCnt)"이 이 값으로
 * 그려지므로 대기·취소를 세면 정원 판단이 부푼다.
 *
 * 일시는 AP-12에 따라 Asia/Seoul 오프셋을 포함해 내려준다.
 */
public record EventSummaryResponse(
        Long eventId,
        String eventClsfCd,
        String eventClsfNm,
        String eventTtl,
        EventStatus eventSttsCd,
        EventPhase eventPhase,
        Long formId,
        FormReceiptStatus receiptStatus,
        OffsetDateTime eventBgngDt,
        OffsetDateTime eventEndDt,
        String plcNm,
        Integer ptcpLmtCnt,
        long confirmedCount,
        OffsetDateTime crtDt,
        OffsetDateTime mdfcnDt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static EventSummaryResponse of(
            EventEntity event,
            EventPhase eventPhase,
            FormReceiptStatus receiptStatus,
            long confirmedCount) {
        return new EventSummaryResponse(
                event.getId(),
                event.getClassification().getCode(),
                event.getClassification().getName(),
                event.getTitle(),
                event.getStatus(),
                eventPhase,
                event.getForm() == null ? null : event.getForm().getId(),
                receiptStatus,
                toOffsetDateTime(event.getBeginAt()),
                toOffsetDateTime(event.getEndAt()),
                event.getPlaceName(),
                event.getParticipantLimitCount(),
                confirmedCount,
                toOffsetDateTime(event.getCreatedAt()),
                toOffsetDateTime(event.getUpdatedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
