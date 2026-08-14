package org.sscc.ssccopsserver.domain.form.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.form.entity.FormLabelRelationEntity;

/*
 * 폼에 지정된 라벨 한 건 (#34 지정 교체 응답).
 *
 * FormLabelResponse를 재사용하지 않는 것은 여기의 crtDt가 라벨이 만들어진 시각이 아니라
 * 이 폼에 달린 시각(form_lbl_rel.crt_dt)이기 때문이다. 같은 이름에 다른 뜻을 담으면
 * 교체 규칙(유지되는 연결은 crt_dt를 보존한다)이 응답에서 검증되지 않는다.
 * usageCount도 지정 화면에는 필요 없는 값이라 싣지 않는다.
 *
 * useYn을 함께 내리는 것은 비활성 라벨이 이미 지정된 채로 남아 있을 수 있어서다 — 화면이
 * 그 칩을 취소선으로 구분해 보여줘야 한다.
 */
public record FormLabelAssignmentResponse(
        Long formLblRelId, Long formLblId, String lblNm, boolean useYn, OffsetDateTime crtDt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static FormLabelAssignmentResponse from(FormLabelRelationEntity relation) {
        return new FormLabelAssignmentResponse(
                relation.getId(),
                relation.getLabel().getId(),
                relation.getLabel().getName(),
                relation.getLabel().isActive(),
                toOffsetDateTime(relation.getCreatedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
