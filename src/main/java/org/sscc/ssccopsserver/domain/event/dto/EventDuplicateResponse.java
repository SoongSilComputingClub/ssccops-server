package org.sscc.ssccopsserver.domain.event.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.event.code.EventStatus;
import org.sscc.ssccopsserver.domain.event.entity.EventEntity;

/*
 * 행사 복제 응답 (ssccops#198 · POST /v1/events/{eventId}/duplicate).
 *
 * 상세(EventDetailResponse)를 그대로 내리지 않는 것은 폼 복제(FormDuplicateResponse)와 같은
 * 판단이다 — 사본은 기간이 비어 있고 참가자가 없어 eventPhase·confirmedCount가 늘 같은 값이
 * 되는데, 늘 같은 값을 내리면 "승계되는 경우도 있나" 하는 의문을 만든다. 사본이 무엇인지
 * 알려 주는 값만 준다 — 화면은 이 eventId로 편집 화면으로 이동한다.
 *
 * formId는 **사본에 연결된 새 폼**이다(결정 1 · 폼도 함께 복제). 원본에 폼이 없었으면 null이다.
 * sourceEventId는 목록에서 여러 행사를 잇달아 복제했을 때 어느 것의 사본인지 화면이 알 수
 * 있게 한다 — 응답이 순서대로 돌아오지 않을 수 있다.
 */
public record EventDuplicateResponse(
        Long eventId,
        Long sourceEventId,
        String eventTtl,
        EventStatus eventSttsCd,
        Long formId,
        OffsetDateTime crtDt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static EventDuplicateResponse of(EventEntity copy, Long sourceEventId) {
        return new EventDuplicateResponse(
                copy.getId(),
                sourceEventId,
                copy.getTitle(),
                copy.getStatus(),
                copy.getForm() == null ? null : copy.getForm().getId(),
                toOffsetDateTime(copy.getCreatedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
