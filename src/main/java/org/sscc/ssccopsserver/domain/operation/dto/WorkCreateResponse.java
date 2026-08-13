package org.sscc.ssccopsserver.domain.operation.dto;

import java.math.BigDecimal;
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
 * workStatus는 서버가 PLANNING으로 고정한 값이며 클라이언트가 지정할 수 없다.
 * 일시는 AP-12에 따라 Asia/Seoul 오프셋을 포함해 내려준다.
 */
public record WorkCreateResponse(
        Long workId,
        Long operationId,
        String title,
        WorkType itemType,
        WorkStatus workStatus,
        Long ownerId,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        OperationPriority priority,
        String review,
        BigDecimal progressRate,
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
                toOffsetDateTime(operation.getBeginAt()),
                toOffsetDateTime(operation.getEndAt()),
                operation.getPriority(),
                work.getGeneralReview(),
                work.getProgressRate(),
                toOffsetDateTime(operation.getCreatedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
