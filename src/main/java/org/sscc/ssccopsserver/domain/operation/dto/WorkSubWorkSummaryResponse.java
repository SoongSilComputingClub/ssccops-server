package org.sscc.ssccopsserver.domain.operation.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.operation.entity.ApprovalStatus;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.WorkStatus;

/*
 * 상위 업무 상세(OPS-003)의 하위 업무 목록 한 행. 화면 우측 표의 '하위 업무명·담당자·단계·
 * 진행률' 네 칸과 행을 클릭해 상세(OPS-009)로 넘어가는 데 필요한 식별자만 담는다.
 *
 * 하위 업무 상세(SubWorkDetailResponse)와 통합하지 않는다 — 목록에는 요약 DTO를 쓴다는
 * AP-14대로이며, 여기서 체크리스트·협업자까지 채우면 행마다 쿼리가 붙는다.
 *
 * approvalStatus는 시안에 없지만 함께 내린다. 승인 대기 배지가 붙을 자리이고, 지금 빼면
 * 그때 계약이 바뀐다.
 */
public record WorkSubWorkSummaryResponse(
        Long subWorkId,
        String title,
        MemberSummaryResponse owner,
        WorkStatus workStatus,
        ApprovalStatus approvalStatus,
        BigDecimal progressRate,
        OffsetDateTime dueAt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    /*
     * 체크리스트 개수는 목록 전체를 한 번에 집계해 넘겨받는다 (DB-13). 항목이 하나도 없는
     * 하위 업무는 집계 결과에 나오지 않으므로 0/0으로 들어온다.
     */
    public static WorkSubWorkSummaryResponse of(
            SubWorkEntity subWork, long completedItems, long totalItems) {
        return new WorkSubWorkSummaryResponse(
                subWork.getId(),
                subWork.getTitle(),
                MemberSummaryResponse.from(subWork.getOperation().getPersonInCharge()),
                subWork.getWorkStatus(),
                subWork.getApprovalStatus(),
                subWork.progressRate(completedItems, totalItems),
                toOffsetDateTime(subWork.getDueAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
