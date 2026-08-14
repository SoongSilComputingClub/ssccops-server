package org.sscc.ssccopsserver.domain.operation.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.operation.entity.SubWorkRejectionEntity;

/*
 * 하위 업무 상세에 실리는 최근 반려 (OPS-009 · #58).
 *
 * 반려 모달이 "반려 사유는 요청자에게 전달됩니다"라고 약속하는데, 그 전달의 실체가 이 필드다 —
 * 알림 채널이 없는 지금 담당자가 사유를 볼 수 있는 유일한 경로이며, 없으면 반려로 진행 단계에
 * 되돌아온 담당자가 무엇을 고쳐야 하는지 알 수 없다.
 *
 * 반려는 하위 업무당 여러 건 쌓이지만(반려 → 보완 → 재검토요청 → 재반려) 상세는 가장 최근
 * 한 건만 싣는다. 전체 이력은 상태 전환 이력(OPS-011)이 맡을 몫이고, 화면이 지금 보여주는 것도
 * '직전에 왜 반려됐는가' 하나다.
 */
public record SubWorkRejectionResponse(
        Long rejectionId,
        MemberSummaryResponse rejector,
        String reason,
        OffsetDateTime rejectedAt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static SubWorkRejectionResponse from(SubWorkRejectionEntity rejection) {
        if (rejection == null) {
            return null;
        }
        return new SubWorkRejectionResponse(
                rejection.getId(),
                MemberSummaryResponse.from(rejection.getRejector()),
                rejection.getReason(),
                toOffsetDateTime(rejection.getRejectedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
