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
 *
 * 주의: parentWorkProgressRate는 저장 컬럼 work_prgrs_rt를 그대로 읽는다. 그 컬럼은 채우지
 * 않기로 결정했으므로(AGG-05, #117) 이 값은 항상 0이며 진행률로 쓸 수 없다 — 정본은
 * AGG-01(하위 업무 진행률의 평균)이고 상세(OPS-003)·목록(OPS-020)이 그것을 내려준다.
 * 여기서 AGG-01을 계산하지 않는 것은 전이마다 집계 쿼리 두 번이 다시 붙기 때문이고(#117이
 * 없앤 것이 바로 그 쿼리다), 필드를 지우지 않은 것은 웹이 이 값을 받지 않아
 * (ssccops-web sub-works.ts — "이 화면에 상위 업무 진행률 표기가 없다") 급하지 않기
 * 때문이다. 화면에 상위 진행률이 필요해지면 이 필드를 살리지 말고 상세를 다시 조회할 것.
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
