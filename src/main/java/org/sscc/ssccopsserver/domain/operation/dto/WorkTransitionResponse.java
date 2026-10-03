package org.sscc.ssccopsserver.domain.operation.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.operation.entity.WorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.WorkStatus;
import org.sscc.ssccopsserver.domain.operation.entity.WorkTransitionAction;

/*
 * 상위 업무 상태 전이 응답 (#622). 화면이 전이 직후 배지·버튼을 다시 조회하지 않고 갱신할 수
 * 있도록 전이 전 상태까지 담는다(MeetingTransitionResponse와 같은 모양).
 */
public record WorkTransitionResponse(
        Long workId,
        WorkTransitionAction transition,
        WorkStatus previousWorkStatus,
        WorkStatus workStatus,
        OffsetDateTime changedAt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static WorkTransitionResponse of(
            WorkEntity work,
            WorkTransitionAction transition,
            WorkStatus previousWorkStatus,
            Instant changedAt) {
        return new WorkTransitionResponse(
                work.getId(),
                transition,
                previousWorkStatus,
                work.getWorkStatus(),
                changedAt.atZone(SERVICE_ZONE).toOffsetDateTime());
    }
}
