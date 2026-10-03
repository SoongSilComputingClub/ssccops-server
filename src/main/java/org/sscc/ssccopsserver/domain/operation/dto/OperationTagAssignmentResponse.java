package org.sscc.ssccopsserver.domain.operation.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.operation.entity.OperationTagRelationEntity;

/*
 * 운영 건에 지정된 태그 한 건 (#637 지정 교체 응답).
 *
 * OperationTagResponse를 재사용하지 않는 것은 여기의 crtDt가 태그가 만들어진 시각이 아니라 **이 운영
 * 건에 달린 시각**(oper_tag_rel.crt_dt)이기 때문이다 — 같은 이름에 다른 뜻을 담으면 «유지되는 지정은
 * 지정 시각이 보존된다»는 교체 규칙이 응답에서 검증되지 않는다 (FormLabelAssignmentResponse와 같다).
 */
public record OperationTagAssignmentResponse(
        Long operationTagRelId, Long operationTagId, String tagNm, OffsetDateTime crtDt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static OperationTagAssignmentResponse from(OperationTagRelationEntity relation) {
        return new OperationTagAssignmentResponse(
                relation.getId(),
                relation.getTag().getId(),
                relation.getTag().getName(),
                toOffsetDateTime(relation.getCreatedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
