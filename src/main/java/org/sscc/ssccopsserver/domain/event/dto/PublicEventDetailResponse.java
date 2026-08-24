package org.sscc.ssccopsserver.domain.event.dto;

import java.time.OffsetDateTime;

import org.sscc.ssccopsserver.domain.event.code.EventPhase;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;
import org.sscc.ssccopsserver.domain.form.code.FormReceiptStatus;

/*
 * 공개 행사 상세 (ssccops#143 · GET /public/v1/events/{eventId}).
 *
 * 계약대로 공개 목록(PublicEventSummaryResponse)에 mtxtCn·ptcpLmtCnt·confirmedCount 셋을 더한
 * 모양이다. 운영자용 EventDetailResponse와 클래스를 나눈 이유는 목록 쪽 주석에 있다.
 *
 * **참가 인원은 숫자까지만 내린다** (D12). "확정 12명 / 정원 20명"은 신청 여부를 판단하는 데
 * 필요한 사실이지만 누가 확정됐는지는 아니다 — 명단은 참가자 본인들에게도 공개하지 않기로 한
 * 결정이라 익명 경로에 실릴 자리가 없다. ptcpLmtCnt가 null이면 정원 무제한이고, 화면은 그때
 * 확정 인원만 표시한다.
 *
 * 본문(mtxtCn)은 md 원문 그대로다 — 렌더링·sanitize는 공개 앱의 안전 렌더러 책임이고 원시
 * HTML은 허용하지 않는다(D12). 서버가 여기서 HTML을 벗기지 않는 것은 저장된 것과 서빙되는 것이
 * 갈리면 운영자가 편집기에서 본 문서와 방문자가 보는 문서가 달라지기 때문이다.
 */
public record PublicEventDetailResponse(
        Long eventId,
        String eventClsfCd,
        String eventClsfNm,
        String eventTtl,
        String mtxtCn,
        String thmbUrlAddr,
        EventPhase eventPhase,
        FormReceiptStatus receiptStatus,
        OffsetDateTime eventBgngDt,
        OffsetDateTime eventEndDt,
        String plcNm,
        Integer ptcpLmtCnt,
        long confirmedCount) {

    public static PublicEventDetailResponse of(
            EventEntity event,
            EventPhase eventPhase,
            FormReceiptStatus receiptStatus,
            long confirmedCount) {
        return new PublicEventDetailResponse(
                event.getId(),
                event.getClassification().getCode(),
                event.getClassification().getName(),
                event.getTitle(),
                event.getContentMarkdown(),
                event.getThumbnailUrlAddress(),
                eventPhase,
                receiptStatus,
                PublicEventSummaryResponse.toOffsetDateTime(event.getBeginAt()),
                PublicEventSummaryResponse.toOffsetDateTime(event.getEndAt()),
                event.getPlaceName(),
                event.getParticipantLimitCount(),
                confirmedCount);
    }
}
