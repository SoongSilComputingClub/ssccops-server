package org.sscc.ssccopsserver.domain.form.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;

/*
 * 템플릿으로 만든 폼 (#142 · POST /v1/form-templates/{formTmplId}/forms).
 *
 * FormDuplicateResponse와 같은 모양이며 sourceFormId 자리에 formTmplId가 들어간다. 두 응답을
 * 하나로 합치지 않은 것은 출처 필드의 뜻이 다르기 때문이다 — 합치면 둘 중 하나는 늘 null인
 * 필드가 두 개 생기고, 프론트는 어느 쪽이 채워지는지 호출한 경로로 되짚어야 한다.
 *
 * 라벨도 응답 수도 싣지 않는다. 템플릿에서 나온 폼은 라벨이 비어 있고 응답이 있을 수 없어
 * 항상 빈 배열과 0이 되는데, 늘 같은 값을 내리면 "혹시 승계되는 경우도 있나" 하는 의문을
 * 만든다 (FormDuplicateResponse와 같은 판단). 접수 기간도 같은 이유로 싣지 않는다 — 둘 다
 * 반드시 null이다.
 *
 * 대신 formSttsCd는 싣는다. 반드시 DRAFT이지만, 그 사실이 이 API의 계약이라 응답에 드러나는
 * 편이 낫다 — 화면은 이 값을 보고 '편집 화면으로 이동'을 고른다.
 */
public record FormFromTemplateResponse(
        Long formId,
        Long formTmplId,
        String formTtlNm,
        FormStatus formSttsCd,
        OffsetDateTime crtDt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static FormFromTemplateResponse of(FormEntity form, Long formTmplId) {
        return new FormFromTemplateResponse(
                form.getId(),
                formTmplId,
                form.getTitle(),
                form.getStatus(),
                toOffsetDateTime(form.getCreatedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
