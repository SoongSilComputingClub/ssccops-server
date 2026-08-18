package org.sscc.ssccopsserver.domain.operation.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import org.sscc.ssccopsserver.domain.operation.entity.ApprovalStatus;
import org.sscc.ssccopsserver.domain.operation.entity.OperationEntity;
import org.sscc.ssccopsserver.domain.operation.entity.OperationPriority;
import org.sscc.ssccopsserver.domain.operation.entity.OperationType;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkChecklistItemEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkTypeEntity;
import org.sscc.ssccopsserver.domain.operation.entity.VoteChoice;
import org.sscc.ssccopsserver.domain.operation.entity.WorkStatus;

/*
 * 하위 업무 상세 조회 응답 (OPS-009). '하위 업무 상세' 화면 한 장이 필요로 하는 값을
 * 한 번의 호출로 채운다 — 단계 스테퍼(workStatus)·공통 속성(oper)·확장 속성(sub_work)·
 * 완료 체크리스트가 모두 여기서 나온다.
 *
 * completionCriteria(cmptn_crtr_cn)는 등록 화면에 입력란이 없어 지금은 늘 NULL이다. 그래도
 * 내리는 것은 값이 없어도 필드는 유지한다는 AP-15 때문이며, 완료 판정은 이 서술이 아니라
 * 체크리스트가 맡는다 — 화면은 이 칸이 비어 있어도 '완료 조건이 없다'로 읽으면 안 된다.
 *
 * 등록 응답(SubWorkCreateResponse)과 필드가 겹치지만 통합하지 않는다. 상세는 담당자를
 * 이름까지 내려야 해서 등록 응답의 ownerId(숫자)를 owner(객체)로 바꿔야 하는데, 그러면
 * 이미 나간 등록 API 계약이 깨진다. 목록·단건의 DTO를 나누는 AP-14와도 같은 방향이다.
 *
 * approvalRequired·authorizerRoleCode는 화면의 "완료 전환은 회장·국장 승인이 필요합니다"
 * 안내를 그리기 위한 값으로, 하위 업무가 아니라 그 유형(sub_work_type)이 갖고 있다.
 *
 * workTitle은 상세 화면의 '상위 업무' 행이다 (#70). 식별자만 내리면 화면이 이름을 얻으려고
 * OPS-003을 한 번 더 불러야 하는데, 그 응답에는 하위 업무 목록까지 실려 있어 이름 한 줄을
 * 위해 상위 업무 한 벌을 통째로 받게 된다. work의 제목은 그 자신이 아니라 상위 oper가 갖고
 * 있으므로 연관을 EntityGraph에 함께 실어 조회 횟수는 그대로 둔다 (DB-13).
 *
 * canApprove·canReject는 **권한만** 답한다 (#58) — "이 사람이 승인·반려할 수 있는 사람인가"이지
 * "지금 누르면 성공하는가"가 아니다. 화면은 이 값으로 버튼을 그릴지 정하고, 누를 수 있는지는
 * workStatus(검토인가)·quorum.met·checklistSummary로 판단한다. 둘을 한 값에 섞으면 정족수가
 * 모자란 승인자와 권한이 아예 없는 사람이 구별되지 않아, 승인자에게도 버튼이 사라진다.
 * 서버가 권한(ApprovalAuthorityPolicy)과 선행 조건(SubWorkEntity)을 나눠 두는 것과 같은 경계다.
 *
 * quorum·myVote는 승인함 카드(OPS-017)와 같은 값이다 — 시안은 승인함이 아니라 이 상세 화면에서
 * 승인·반려를 누르므로, 같은 판단 근거가 여기에도 있어야 한다. 정족수 유형이 아니면 quorum.needed가
 * false이고 나머지는 NULL이다.
 *
 * 일시는 AP-12에 따라 Asia/Seoul 오프셋을 포함해 내려준다.
 */
public record SubWorkDetailResponse(
        Long subWorkId,
        Long operationId,
        Long workId,
        String workTitle,
        OperationType operationType,
        String title,
        Long subWorkTypeId,
        String subWorkTypeName,
        WorkStatus workStatus,
        ApprovalStatus approvalStatus,
        boolean approvalRequired,
        String authorizerRoleCode,
        MemberSummaryResponse owner,
        MemberSummaryResponse registrant,
        List<MemberSummaryResponse> collaborators,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        OffsetDateTime dueAt,
        OperationPriority priority,
        String content,
        String completionCriteria,
        String externalLink,
        boolean isDelayed,
        OffsetDateTime completedAt,
        List<SubWorkChecklistItemResponse> checklist,
        SubWorkChecklistSummaryResponse checklistSummary,
        ApprovalQuorumResponse quorum,
        VoteChoice myVote,
        SubWorkRejectionResponse latestRejection,
        boolean canApprove,
        boolean canReject,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    /*
     * 협업자는 항상 빈 목록이다. 배정 테이블(sub_work_pic_altmnt)이 아직 매핑되지 않았고
     * 등록 API도 채우지 않는다. 필드를 생략하지 않는 것은 값이 없어도 필드는 내린다는
     * AP-15 때문이며, 클라이언트가 배정 기능이 붙을 때 타입을 바꾸지 않아도 되게 한다.
     */
    private static final List<MemberSummaryResponse> NO_COLLABORATORS = List.of();

    /*
     * delayed는 엔티티의 dly_yn 컬럼이 아니라 조회 시점에 판정한 값이다 (SubWorkEntity.isDelayedBefore).
     * 조회가 컬럼을 갱신하지는 않으므로(AP-07) 저장된 값과 어긋날 수 있다.
     *
     * canDecide 하나로 canApprove·canReject를 함께 채운다 — 승인과 반려의 권한 규칙이 지금은
     * 같기 때문이다(둘 다 유형이 지정한 승인자). 그럼에도 응답 필드를 둘로 나눠 두는 것은
     * 자가 승인 차단(O-04)이 확정되면 승인만 false가 되기 때문이며, 그때 프론트가 버튼 하나의
     * 조건식을 고치지 않아도 되게 한다.
     */
    public static SubWorkDetailResponse of(
            SubWorkEntity subWork,
            List<SubWorkChecklistItemEntity> checklist,
            boolean delayed,
            ApprovalQuorumResponse quorum,
            VoteChoice myVote,
            SubWorkRejectionResponse latestRejection,
            boolean canDecide) {
        OperationEntity operation = subWork.getOperation();
        SubWorkTypeEntity subWorkType = subWork.getSubWorkType();
        List<SubWorkChecklistItemResponse> checklistItems =
                checklist.stream().map(SubWorkChecklistItemResponse::from).toList();

        return new SubWorkDetailResponse(
                subWork.getId(),
                operation.getId(),
                subWork.getWork().getId(),
                subWork.getWork().getOperation().getTitle(),
                operation.getOperationType(),
                subWork.getTitle(),
                subWorkType.getId(),
                subWorkType.getTypeName(),
                subWork.getWorkStatus(),
                subWork.getApprovalStatus(),
                subWorkType.isApprovalNeeded(),
                subWorkType.getAuthorizerRoleCode(),
                MemberSummaryResponse.from(operation.getPersonInCharge()),
                MemberSummaryResponse.from(operation.getRegistrant()),
                NO_COLLABORATORS,
                toOffsetDateTime(operation.getBeginAt()),
                toOffsetDateTime(operation.getEndAt()),
                toOffsetDateTime(subWork.getDueAt()),
                operation.getPriority(),
                subWork.getContent(),
                subWork.getCompletionCriteria(),
                subWork.getExternalLink(),
                delayed,
                toOffsetDateTime(subWork.getCompletedAt()),
                checklistItems,
                SubWorkChecklistSummaryResponse.from(checklistItems),
                quorum,
                myVote,
                latestRejection,
                canDecide,
                canDecide,
                toOffsetDateTime(operation.getCreatedAt()),
                toOffsetDateTime(operation.getUpdatedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
