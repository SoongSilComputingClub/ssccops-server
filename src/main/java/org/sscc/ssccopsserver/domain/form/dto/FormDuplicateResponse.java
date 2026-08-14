package org.sscc.ssccopsserver.domain.form.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;

/*
 * 폼 복제 응답 (#32 · POST /v1/forms/{formId}/duplicate).
 *
 * 목록·상세와 달리 라벨도 응답 수도 싣지 않는다. 사본은 라벨을 승계하지 않고 응답도 없어서
 * 항상 빈 배열과 0이 되는데, 늘 같은 값을 내리면 "혹시 승계되는 경우도 있나" 하는 의문을
 * 만든다. 사본이 무엇인지 알려주는 값만 준다 — 화면은 이 formId로 상세나 편집으로 이동한다.
 *
 * sourceFormId를 함께 내리는 것은 목록에서 여러 폼을 잇달아 복제했을 때 어느 폼의 사본인지
 * 프론트가 알 수 있게 하기 위해서다. 응답이 순서대로 돌아오지 않을 수 있다.
 */
public record FormDuplicateResponse(
        Long formId,
        Long sourceFormId,
        String formTtlNm,
        FormStatus formSttsCd,
        OffsetDateTime crtDt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static FormDuplicateResponse of(FormEntity copy, Long sourceFormId) {
        return new FormDuplicateResponse(
                copy.getId(),
                sourceFormId,
                copy.getTitle(),
                copy.getStatus(),
                toOffsetDateTime(copy.getCreatedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
