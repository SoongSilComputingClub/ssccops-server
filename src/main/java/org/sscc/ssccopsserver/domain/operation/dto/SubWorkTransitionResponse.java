package org.sscc.ssccopsserver.domain.operation.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.operation.entity.ApprovalStatus;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.TransitionAction;
import org.sscc.ssccopsserver.domain.operation.entity.WorkStatus;

/*
 * 하위 업무 상태 전이 응답 (OPS-010 · TransitionResult).
 *
 * 정의서가 명시한 필드는 workStatus·approvalStatus 둘뿐이나, 상세 화면이 전이 직후
 * 스테퍼·승인 칩·상위 진행률을 다시 조회하지 않고 갱신할 수 있도록 전이 전 상태와
 * 완료 일시·상위 진행률까지 담는다.
 *
 * isSelfApproval은 OPS-014(승인함)의 필드지만 자가 승인 판정은 이 전이에서도 똑같이
 * 일어난다. 승인·완료가 아닌 전이에서는 항상 false다 (POL-006 — 차단이 아니라 표시).
 */
public record SubWorkTransitionResponse(
        Long subWorkId,
        TransitionAction transition,
        WorkStatus previousWorkStatus,
        WorkStatus workStatus,
        ApprovalStatus previousApprovalStatus,
        ApprovalStatus approvalStatus,
        boolean isSelfApproval,
        OffsetDateTime completedAt,
        BigDecimal parentWorkProgressRate,
        OffsetDateTime changedAt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static SubWorkTransitionResponse of(
            SubWorkEntity subWork,
            TransitionAction transition,
            WorkStatus previousWorkStatus,
            ApprovalStatus previousApprovalStatus,
            boolean selfApproval,
            Instant changedAt) {
        return new SubWorkTransitionResponse(
                subWork.getId(),
                transition,
                previousWorkStatus,
                subWork.getWorkStatus(),
                previousApprovalStatus,
                subWork.getApprovalStatus(),
                selfApproval,
                toOffsetDateTime(subWork.getCompletedAt()),
                subWork.getWork().getProgressRate(),
                toOffsetDateTime(changedAt));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
