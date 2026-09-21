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
 *
 * **academicProgram(#519 · ssccops#435 · ADR-0043)은 이 행사가 학술 프로그램(스터디·프로젝트·
 * 트랙)일 때 그 식별자·유형이고, 아니면 null이다.** 판정은 분류가 아니라 acdm_actv 행의 존재이며
 * 유형 어휘는 acdm_actv_type이다 — 왜 분류로 가르지 않는지는 AcademicProgramRef 주석.
 * 편집 화면은 이 값이 있으면 분류 칸을 잠근다 — 서버도 분류 변경을 409로 거절한다
 * (EVENT_CLASSIFICATION_LOCKED_FOR_PROGRAM). 요청(EventSaveRequest)에는 이 필드가 없다 —
 * 구조에서 파생한 값이라 클라이언트가 보낼 것이 아니다.
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
        OffsetDateTime mdfcnDt,
        AcademicProgramRef academicProgram) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static EventDetailResponse of(
            EventEntity event,
            EventPhase eventPhase,
            FormReceiptStatus receiptStatus,
            long confirmedCount,
            AcademicProgramRef academicProgram) {
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
                toOffsetDateTime(event.getUpdatedAt()),
                academicProgram);
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
