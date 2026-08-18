package org.sscc.ssccopsserver.domain.operation.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import org.sscc.ssccopsserver.domain.operation.entity.ApprovalStatus;
import org.sscc.ssccopsserver.domain.operation.entity.OperationEntity;
import org.sscc.ssccopsserver.domain.operation.entity.OperationPriority;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkChecklistItemEntity;
import org.sscc.ssccopsserver.domain.operation.entity.SubWorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.WorkStatus;

/*
 * 하위 업무 등록 응답 (OPS-007).
 *
 * workStatus는 서버가 PLANNING으로 고정한 값이고, approvalStatus는 유형의 승인 필요
 * 여부에서 결정된 값이고, registrantId는 인증 주체에서 온 등록자라 모두 클라이언트가
 * 지정할 수 없다. 담당자(ownerId)와 등록자는 다를 수 있다.
 * checklist는 유형의 완료 점검 항목을 복사해 만든 것으로, 화면 안내대로 등록과 동시에 생긴다.
 * 일시는 AP-12에 따라 Asia/Seoul 오프셋을 포함해 내려준다.
 */
public record SubWorkCreateResponse(
        Long subWorkId,
        Long operationId,
        Long workId,
        String title,
        Long subWorkTypeId,
        String subWorkTypeName,
        WorkStatus workStatus,
        ApprovalStatus approvalStatus,
        Long ownerId,
        Long registrantId,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        OffsetDateTime dueAt,
        OperationPriority priority,
        String content,
        String externalLink,
        boolean isDelayed,
        List<SubWorkChecklistItemResponse> checklist,
        OffsetDateTime createdAt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    /*
     * isDelayed는 dly_yn 컬럼이 아니라 조회 시점 판정값을 받는다 (#121). 원래 이 응답만
     * 컬럼을 읽고 있었는데, 그 컬럼은 채우지 않기로 결정한 값이라(#117) 마감이 이미 지난
     * 건으로 등록해도 여기서만 false가 나가 목록·상세와 갈렸다.
     */
    public static SubWorkCreateResponse of(
            SubWorkEntity subWork, List<SubWorkChecklistItemEntity> checklist, boolean delayed) {
        OperationEntity operation = subWork.getOperation();
        return new SubWorkCreateResponse(
                subWork.getId(),
                operation.getId(),
                subWork.getWork().getId(),
                subWork.getTitle(),
                subWork.getSubWorkType().getId(),
                subWork.getSubWorkType().getTypeName(),
                subWork.getWorkStatus(),
                subWork.getApprovalStatus(),
                operation.getPersonInCharge().getId(),
                operation.getRegistrant() == null ? null : operation.getRegistrant().getId(),
                toOffsetDateTime(operation.getBeginAt()),
                toOffsetDateTime(operation.getEndAt()),
                toOffsetDateTime(subWork.getDueAt()),
                operation.getPriority(),
                subWork.getContent(),
                subWork.getExternalLink(),
                delayed,
                checklist.stream().map(SubWorkChecklistItemResponse::from).toList(),
                toOffsetDateTime(operation.getCreatedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
