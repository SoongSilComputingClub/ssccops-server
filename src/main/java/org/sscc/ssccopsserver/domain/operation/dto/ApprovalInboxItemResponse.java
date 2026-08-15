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
 *
 * latestRejectionReason은 반려 카드의 `반려 사유 · …` 한 줄이다 (#62). 상세(OPS-009)가 객체
 * (SubWorkRejectionResponse — 반려자·반려 일시까지)를 내리는 것과 달리 목록은 사유 문자열
 * 하나만 싣는다: 카드가 그리는 것이 그 한 줄이고, 반려자를 실으려면 카드 수만큼 회원 조회가
 * 따라붙는다 (목록과 단건의 DTO를 나누는 AP-14와 같은 방향).
 *
 * 반려 탭에서만 채우지 않고 **탭과 무관하게** 직전 반려의 사유를 싣는다. 반려 후 다시 올라온
 * 건(REAPPROVAL_REQUIRED)은 대기 탭에 있는데, 승인자가 "무엇이 걸려서 되돌아왔던 건인지"를
 * 알아야 하는 자리가 바로 거기다. 반려된 적이 없으면 NULL이다.
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
        VoteChoice myVote,
        String latestRejectionReason) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static ApprovalInboxItemResponse of(
            SubWorkEntity subWork,
            Instant requestedAt,
            long agreedVoteCount,
            long completedItems,
            long totalItems,
            VoteChoice myVote,
            String latestRejectionReason) {
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
                myVote,
                latestRejectionReason);
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
