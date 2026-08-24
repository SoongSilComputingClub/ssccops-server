package org.sscc.ssccopsserver.domain.event.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.event.code.EventPhase;
import org.sscc.ssccopsserver.domain.event.code.EventStatus;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.form.code.FormReceiptStatus;

/*
 * 행사 단건 상세 (ssccops#139 · GET /v1/events/{eventId} 및 생성·수정·전이 응답).
 *
 * 계약대로 목록(EventSummaryResponse)의 모든 필드에 mtxtCn·thmbUrlAddr를 더한 모양이다.
 * 편집 화면이 이 응답을 그대로 초안으로 받아 PUT으로 돌려보내므로 요청(EventSaveRequest)과
 * 필드 이름이 일치해야 한다 (FormDetailResponse 선례).
 *
 * 상태 전이 응답도 이 DTO다 — 전이 직후 화면이 배지(eventSttsCd)·단계(eventPhase)를 다시
 * 그리는데, 상태만 돌려주면 프론트가 상세를 한 번 더 조회하게 된다.
 */
public record EventDetailResponse(
        Long eventId,
        String eventClsfCd,
        String eventClsfNm,
        String eventTtl,
        String mtxtCn,
        String thmbUrlAddr,
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

    public static EventDetailResponse of(
            EventEntity event,
            EventPhase eventPhase,
            FormReceiptStatus receiptStatus,
            long confirmedCount) {
        return new EventDetailResponse(
                event.getId(),
                event.getClassification().getCode(),
                event.getClassification().getName(),
                event.getTitle(),
                event.getContentMarkdown(),
                event.getThumbnailUrlAddress(),
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
