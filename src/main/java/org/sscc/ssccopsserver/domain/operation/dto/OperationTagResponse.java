package org.sscc.ssccopsserver.domain.operation.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.operation.entity.OperationTagEntity;

/*
 * 운영 태그 한 건 (#637 태그 목록·생성·이름 변경 응답).
 *
 * 식별자 이름은 operationTagId다 — 목록·상세의 칩(OperationTagSummaryResponse)·지정 응답과 같은
 * 이름이라 화면이 어느 응답에서 받은 id든 그대로 tagIds·tagId에 넣는다. tagNm이 컬럼명을 따르는 것은
 * 폼 라벨(FormLabelResponse의 lblNm)과 모양을 맞추려는 것이다. usageCount는 그 태그가 달린 **살아
 * 있는** 운영 건 수다 — 업무·하위 업무·회의를 합친다(지운 건은 세지 않는다 ·
 * OperationTagRelationRepository 주석).
 *
 * 일시는 AP-12에 따라 Asia/Seoul 오프셋을 포함해 내려준다.
 */
public record OperationTagResponse(
        Long operationTagId,
        String tagNm,
        long usageCount,
        OffsetDateTime crtDt,
        OffsetDateTime mdfcnDt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static OperationTagResponse of(OperationTagEntity tag, long usageCount) {
        return new OperationTagResponse(
                tag.getId(),
                tag.getName(),
                usageCount,
                toOffsetDateTime(tag.getCreatedAt()),
                toOffsetDateTime(tag.getUpdatedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
