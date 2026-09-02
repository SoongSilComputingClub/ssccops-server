package org.sscc.ssccopsserver.domain.event.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.event.code.EventPhase;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.form.code.FormReceiptStatus;

/*
 * 공개 행사 목록 항목 (ssccops#143 · GET /public/v1/events). 필드명은 공개 앱과 합의된 계약이다.
 *
 * **운영자용 EventSummaryResponse와 클래스를 나눈 것이 이 DTO의 존재 이유다** (PublicFormResponse
 * 선례). 한 record를 공유하면서 공개 경로에서만 몇 필드를 비우는 방식은, 운영자용 응답에 필드가
 * 하나 늘 때마다 그것이 공개 링크로 새어 나가지 않는지를 사람이 매번 확인해야 한다는 뜻이다 —
 * 그 확인은 언젠가 빠진다. 나눠 두면 공개 응답에 무엇이 실리는지가 이 파일 하나로 끝난다.
 *
 * 그래서 여기 **없는** 것이 무엇인지가 있는 것만큼 중요하다 (D12):
 *   creatrMbrId(작성자) · eventSttsCd(저장 상태) · formId(연결 폼 식별자) · confirmedCount ·
 *   참가자 명단 · 폼 문항 · crtDt/mdfcnDt(운영 타임스탬프).
 * 목록에 나오는 행사는 정의상 PUBLISHED뿐이라 eventSttsCd를 실을 이유도 없다.
 *
 * 본문(mtxtCn)도 목록에는 싣지 않는다 — 운영자용 목록과 같은 이유이고(10만 자 × 행사 수),
 * 목록 화면이 본문을 그리지 않는다.
 *
 * eventPhase는 행사 일시에서 조회 시점에 파생한 값이고(EventPhasePolicy), receiptStatus는
 * 연결된 폼의 파생 값이다(EventReceiptPolicy) — 폼이 없는 공지는 null이다.
 *
 * 일시는 AP-12에 따라 Asia/Seoul 오프셋을 포함해 내려준다.
 */
public record PublicEventSummaryResponse(
        Long eventId,
        String eventClsfCd,
        String eventClsfNm,
        String eventTtl,
        String thmbUrlAddr,
        EventPhase eventPhase,
        FormReceiptStatus receiptStatus,
        OffsetDateTime eventBgngDt,
        OffsetDateTime eventEndDt,
        String plcNm) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static PublicEventSummaryResponse of(
            EventEntity event, EventPhase eventPhase, FormReceiptStatus receiptStatus) {
        return new PublicEventSummaryResponse(
                event.getId(),
                event.getClassification().getCode(),
                event.getClassification().getName(),
                event.getTitle(),
                event.getThumbnailUrlAddress(),
                eventPhase,
                receiptStatus,
                toOffsetDateTime(event.getBeginAt()),
                toOffsetDateTime(event.getEndAt()),
                event.getPlaceName());
    }

    static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
