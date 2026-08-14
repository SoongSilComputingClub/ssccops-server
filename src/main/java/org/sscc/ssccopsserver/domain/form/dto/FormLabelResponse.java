package org.sscc.ssccopsserver.domain.form.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.form.entity.FormLabelEntity;

/*
 * 폼 라벨 한 건 (#34 라벨 목록·생성·토글 응답).
 *
 * 필드명이 formLblId·lblNm·useYn으로 컬럼명을 그대로 따르는 것은 웹이 이미 이 이름으로
 * 소비하고 있기 때문이다(entities/form/model/types.ts의 FormLbl). 다른 도메인의 응답 DTO는
 * 도메인 용어를 쓰지만, 여기서 이름을 바꾸면 화면과 계약이 어긋난다.
 *
 * usageCount는 엔티티에 없는 값이라 팩토리가 따로 받는다 — 라벨마다 세면 N+1이 되므로
 * 집계는 서비스가 한 번에 해서 넘긴다.
 *
 * 일시는 AP-12에 따라 Asia/Seoul 오프셋을 포함해 내려준다.
 */
public record FormLabelResponse(
        Long formLblId,
        String lblNm,
        boolean useYn,
        long usageCount,
        OffsetDateTime crtDt,
        OffsetDateTime mdfcnDt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static FormLabelResponse of(FormLabelEntity label, long usageCount) {
        return new FormLabelResponse(
                label.getId(),
                label.getName(),
                label.isActive(),
                usageCount,
                toOffsetDateTime(label.getCreatedAt()),
                toOffsetDateTime(label.getUpdatedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
