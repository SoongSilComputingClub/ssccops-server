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
import org.sscc.ssccopsserver.domain.operation.entity.WorkStatus;

/*
 * 하위 업무 상세 조회 응답 (OPS-009). '하위 업무 상세' 화면 한 장이 필요로 하는 값을
 * 한 번의 호출로 채운다 — 단계 스테퍼(workStatus)·공통 속성(oper)·확장 속성(sub_work)·
 * 완료 체크리스트가 모두 여기서 나온다.
 *
 * 등록 응답(SubWorkCreateResponse)과 필드가 겹치지만 통합하지 않는다. 상세는 담당자를
 * 이름까지 내려야 해서 등록 응답의 ownerId(숫자)를 owner(객체)로 바꿔야 하는데, 그러면
 * 이미 나간 등록 API 계약이 깨진다. 목록·단건의 DTO를 나누는 AP-14와도 같은 방향이다.
 *
 * approvalRequired·authorizerRoleCode는 화면의 "완료 전환은 회장·국장 승인이 필요합니다"
 * 안내를 그리기 위한 값으로, 하위 업무가 아니라 그 유형(sub_work_type)이 갖고 있다.
 *
 * 일시는 AP-12에 따라 Asia/Seoul 오프셋을 포함해 내려준다.
 */
public record SubWorkDetailResponse(
        Long subWorkId,
        Long operationId,
        Long workId,
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
        String externalLink,
        boolean isDelayed,
        OffsetDateTime completedAt,
        List<SubWorkChecklistItemResponse> checklist,
        SubWorkChecklistSummaryResponse checklistSummary,
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
     * delayed는 엔티티의 dly_yn 컬럼이 아니라 조회 시점에 판정한 값이다 (SubWorkEntity.isDelayedAt).
     * 조회가 컬럼을 갱신하지는 않으므로(AP-07) 저장된 값과 어긋날 수 있다.
     */
    public static SubWorkDetailResponse of(
            SubWorkEntity subWork, List<SubWorkChecklistItemEntity> checklist, boolean delayed) {
        OperationEntity operation = subWork.getOperation();
        SubWorkTypeEntity subWorkType = subWork.getSubWorkType();
        List<SubWorkChecklistItemResponse> checklistItems =
                checklist.stream().map(SubWorkChecklistItemResponse::from).toList();

        return new SubWorkDetailResponse(
                subWork.getId(),
                operation.getId(),
                subWork.getWork().getId(),
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
                subWork.getExternalLink(),
                delayed,
                toOffsetDateTime(subWork.getCompletedAt()),
                checklistItems,
                SubWorkChecklistSummaryResponse.from(checklistItems),
                toOffsetDateTime(operation.getCreatedAt()),
                toOffsetDateTime(operation.getUpdatedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
