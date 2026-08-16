package org.sscc.ssccopsserver.domain.operation.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.operation.entity.AttendeeScope;
import org.sscc.ssccopsserver.domain.operation.entity.MeetingCategory;
import org.sscc.ssccopsserver.domain.operation.entity.MeetingEntity;
import org.sscc.ssccopsserver.domain.operation.entity.MeetingStatus;
import org.sscc.ssccopsserver.domain.operation.entity.OperationEntity;

/*
 * 회의 목록 응답 (신규 · GET /v1/meetings). '회의' 화면의 카드 그리드 한 장이 이 호출
 * 하나다. 정의서에 목록 API가 없어 결번을 새로 부여한 리소스다(WorkListItemResponse와
 * 같은 판단).
 *
 * 안건 건수(agendaCount)만 싣고 목록 자체는 싣지 않는다 — 카드는 "안건 N건"만 그리고,
 * 펼친 안건은 상세(OPS-025) 진입 후에나 필요하다. 페이지 봉투가 없는 것은 이 목록이
 * 커서 페이징을 쓰지 않기 때문이다(MeetingRepository 주석 참고).
 */
public record MeetingListItemResponse(
        Long meetingId,
        Long operationId,
        String title,
        MeetingCategory meetingCategory,
        MeetingStatus meetingStatus,
        AttendeeScope attendeeScope,
        MemberSummaryResponse personInCharge,
        String location,
        int agendaCount,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        OffsetDateTime createdAt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static MeetingListItemResponse of(MeetingEntity meeting, int agendaCount) {
        OperationEntity operation = meeting.getOperation();
        return new MeetingListItemResponse(
                meeting.getId(),
                operation.getId(),
                operation.getTitle(),
                meeting.getMeetingCategory(),
                meeting.getMeetingStatus(),
                meeting.getAttendeeScope(),
                MemberSummaryResponse.from(operation.getPersonInCharge()),
                meeting.getLocation(),
                agendaCount,
                toOffsetDateTime(operation.getBeginAt()),
                toOffsetDateTime(operation.getEndAt()),
                toOffsetDateTime(operation.getCreatedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
