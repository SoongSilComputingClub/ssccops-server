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
 * delDt(#347)는 **살아 있는 행사에서 언제나 null이고 휴지통 목록(GET /v1/events/deleted)에서만
 * 값이 있다.** 지운 행사는 목록 질의에서 통째로 빠지므로 두 목록이 한 화면에 섞이지 않으며,
 * 그래서 이 필드 하나로 두 목록이 같은 카드를 그린다 — 휴지통 전용 DTO를 따로 만들면 행사
 * 목록에 필드가 늘 때마다 한쪽만 늘어 두 화면이 갈린다(FormSummaryResponse.delDt와 같은 판단).
 * 빼지 않고 null로 내리는 것은 AP-15(값이 없어도 필드는 내린다)다.
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
        OffsetDateTime mdfcnDt,
        OffsetDateTime delDt) {

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
                toOffsetDateTime(event.getUpdatedAt()),
                toOffsetDateTime(event.getDeletedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
