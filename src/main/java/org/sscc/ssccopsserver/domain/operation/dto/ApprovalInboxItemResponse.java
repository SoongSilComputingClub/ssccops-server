package org.sscc.ssccopsserver.domain.operation.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.member.entity.MemberEntity;
import org.sscc.ssccopsserver.domain.operation.entity.ApprovalStatus;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.VoteChoice;

/*
 * 승인함 카드 한 장 (OPS-017 · #47).
 *
 * 시안에 보이는 값 중 두 가지는 싣지 않는다.
 *  - `긴급` 배지: 디자인에서 제외하기로 확정했다. 우선순위(oper.prrty_rnk_cd)는 하위 업무
 *    목록(OPS-008)에 이미 있으므로 컬럼은 그대로 두고 이 응답에만 넣지 않는다.
 *  - `n단계`: sub_work_aprv.aprv_stp(위험도 기반 승인 단계)를 채우는 경로가 없어 항상 NULL이다.
 *    없는 값을 계약에 넣지 않는다.
 *
 * 반대로 시안에 없는 checklistSummary는 싣는다. 체크리스트가 덜 찬 건은 `승인`이 409로
 * 떨어지는데, 카드에 근거가 없으면 화면이 이유를 설명할 수 없다.
 */
public record ApprovalInboxItemResponse(
        Long subWorkId,
        String title,
        ApprovalStatus approvalStatus,
        String subWorkTypeName,
        String authorizerRoleCode,
        String registrantName,
        OffsetDateTime requestedAt,
        OffsetDateTime dueAt,
        ApprovalQuorumResponse quorum,
        SubWorkChecklistSummaryResponse checklistSummary,
        VoteChoice myVote) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static ApprovalInboxItemResponse of(
            SubWorkEntity subWork,
            Instant requestedAt,
            long agreedVoteCount,
            long completedItems,
            long totalItems,
            VoteChoice myVote) {
        MemberEntity registrant = subWork.getOperation().getRegistrant();
        return new ApprovalInboxItemResponse(
                subWork.getId(),
                subWork.getTitle(),
                subWork.getApprovalStatus(),
                subWork.getSubWorkType().getTypeName(),
                subWork.getSubWorkType().getAuthorizerRoleCode(),
                registrant == null ? null : registrant.getName(),
                toOffsetDateTime(requestedAt),
                toOffsetDateTime(subWork.getDueAt()),
                ApprovalQuorumResponse.of(subWork.getSubWorkType(), agreedVoteCount),
                new SubWorkChecklistSummaryResponse(completedItems, totalItems),
                myVote);
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
