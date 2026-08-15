package org.sscc.ssccopsserver.domain.operation.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.operation.entity.MeetingEntity;
import org.sscc.ssccopsserver.domain.operation.entity.MeetingStatus;
import org.sscc.ssccopsserver.domain.operation.entity.MeetingTransitionAction;

/*
 * 회의 상태 전이 응답 (OPS-026 · TransitionResult). 정의서가 명시한 필드는 meetingStatus
 * 하나뿐이나, 상세 화면이 전이 직후 배지를 다시 조회하지 않고 갱신할 수 있도록 전이 전
 * 상태까지 담는다(SubWorkTransitionResponse와 같은 판단).
 */
public record MeetingTransitionResponse(
        Long meetingId,
        MeetingTransitionAction transition,
        MeetingStatus previousMeetingStatus,
        MeetingStatus meetingStatus,
        OffsetDateTime changedAt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static MeetingTransitionResponse of(
            MeetingEntity meeting,
            MeetingTransitionAction transition,
            MeetingStatus previousMeetingStatus,
            Instant changedAt) {
        return new MeetingTransitionResponse(
                meeting.getId(),
                transition,
                previousMeetingStatus,
                meeting.getMeetingStatus(),
                toOffsetDateTime(changedAt));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
