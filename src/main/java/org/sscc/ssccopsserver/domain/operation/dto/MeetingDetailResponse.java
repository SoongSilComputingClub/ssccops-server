package org.sscc.ssccopsserver.domain.operation.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import org.sscc.ssccopsserver.domain.operation.entity.AttendeeScope;
import org.sscc.ssccopsserver.domain.operation.entity.MeetingCategory;
import org.sscc.ssccopsserver.domain.operation.entity.MeetingEntity;
import org.sscc.ssccopsserver.domain.operation.entity.MeetingStatus;
import org.sscc.ssccopsserver.domain.operation.entity.OperationEntity;
import org.sscc.ssccopsserver.domain.operation.entity.OperationPriority;
import org.sscc.ssccopsserver.domain.operation.entity.OperationType;

/*
 * 회의 상세 조회 응답 (OPS-025). '회의 상세' 화면이 진입 시 호출하며, 등록(OPS-024)·
 * 전이(OPS-026)도 화면을 재조회 없이 갱신할 수 있도록 같은 모양을 쓴다.
 *
 * work·sub_work의 상세 응답(WorkDetailResponse)과 같은 판단으로, 화면이 '공통 속성 · oper'와
 * '확장 속성 · mtg' 두 블록으로 나눠 보여주지만 응답은 한 단계 평면 구조다.
 *
 * 일시는 AP-12에 따라 Asia/Seoul 오프셋을 포함해 내려준다.
 */
public record MeetingDetailResponse(
        Long meetingId,
        Long operationId,
        OperationType operationType,
        String title,
        MeetingCategory meetingCategory,
        MeetingStatus meetingStatus,
        AttendeeScope attendeeScope,
        MemberSummaryResponse personInCharge,
        MemberSummaryResponse registrant,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        OperationPriority priority,
        String location,
        String internalDetail,
        String externalSummary,
        List<MeetingAgendaResponse> agendas,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static MeetingDetailResponse of(
            MeetingEntity meeting, List<MeetingAgendaResponse> agendas) {
        OperationEntity operation = meeting.getOperation();
        return new MeetingDetailResponse(
                meeting.getId(),
                operation.getId(),
                operation.getOperationType(),
                operation.getTitle(),
                meeting.getMeetingCategory(),
                meeting.getMeetingStatus(),
                meeting.getAttendeeScope(),
                MemberSummaryResponse.from(operation.getPersonInCharge()),
                MemberSummaryResponse.from(operation.getRegistrant()),
                toOffsetDateTime(operation.getBeginAt()),
                toOffsetDateTime(operation.getEndAt()),
                operation.getPriority(),
                meeting.getLocation(),
                meeting.getInternalDetail(),
                meeting.getExternalSummary(),
                agendas,
                toOffsetDateTime(operation.getCreatedAt()),
                toOffsetDateTime(operation.getUpdatedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
