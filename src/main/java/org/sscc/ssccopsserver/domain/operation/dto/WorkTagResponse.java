package org.sscc.ssccopsserver.domain.operation.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.operation.entity.WorkTagEntity;

/*
 * 업무 태그 한 건 (#624 태그 목록·생성·이름 변경 응답).
 *
 * 필드명(workTagId·tagNm)이 컬럼명을 따르는 것은 폼 라벨(FormLabelResponse의 formLblId·lblNm)과
 * 모양을 맞추려는 것이다 — 웹이 칩 선택기를 한 벌로 쓴다. usageCount는 그 태그가 달린 **살아 있는**
 * 업무 수다(지운 업무는 세지 않는다 · WorkTagRelationRepository 주석).
 *
 * 일시는 AP-12에 따라 Asia/Seoul 오프셋을 포함해 내려준다.
 */
public record WorkTagResponse(
        Long workTagId,
        String tagNm,
        long usageCount,
        OffsetDateTime crtDt,
        OffsetDateTime mdfcnDt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static WorkTagResponse of(WorkTagEntity tag, long usageCount) {
        return new WorkTagResponse(
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
