package org.sscc.ssccopsserver.domain.operation.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.operation.entity.OperationEntity;
import org.sscc.ssccopsserver.domain.operation.entity.OperationPriority;
import org.sscc.ssccopsserver.domain.operation.entity.WorkEntity;
import org.sscc.ssccopsserver.domain.operation.entity.WorkStatus;
import org.sscc.ssccopsserver.domain.operation.entity.WorkType;

/*
 * 업무 등록 응답 (OPS-002).
 *
 * workStatus는 서버가 PLANNING으로 고정한 값이며, registrantId는 인증 주체에서 온 등록자라
 * 둘 다 클라이언트가 지정할 수 없다. 담당자(ownerId)와 등록자는 다를 수 있다.
 * 일시는 AP-12에 따라 Asia/Seoul 오프셋을 포함해 내려준다.
 *
 * 진행률은 싣지 않는다 (#595). 등록 직후라 하위 업무가 없고, 진행률은 저장하지 않고 상세
 * (OPS-003)·목록(OPS-020)이 AGG-01로 계산해 준다. 예전에는 저장 컬럼 work_prgrs_rt를 그대로
 * 실어 늘 0이 나갔다.
 */
public record WorkCreateResponse(
        Long workId,
        Long operationId,
        String title,
        WorkType itemType,
        WorkStatus workStatus,
        Long ownerId,
        Long registrantId,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        OperationPriority priority,
        String review,
        OffsetDateTime createdAt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static WorkCreateResponse from(WorkEntity work) {
        OperationEntity operation = work.getOperation();
        return new WorkCreateResponse(
                work.getId(),
                operation.getId(),
                operation.getTitle(),
                work.getWorkType(),
                work.getWorkStatus(),
                operation.getPersonInCharge().getId(),
                operation.getRegistrant() == null ? null : operation.getRegistrant().getId(),
                toOffsetDateTime(operation.getBeginAt()),
                toOffsetDateTime(operation.getEndAt()),
                operation.getPriority(),
                work.getGeneralReview(),
                toOffsetDateTime(operation.getCreatedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
