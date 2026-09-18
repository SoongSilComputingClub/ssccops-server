package org.sscc.ssccopsserver.domain.form.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

import org.sscc.ssccopsserver.domain.form.entity.FormEntity;

/*
 * 익명용 «접수 중인 폼» 한 건 (ssccops#381 · ADR-0038). 실리는 것은 **폼 키·제목·마감** 셋뿐이다 —
 * 접수 시작일·상태·문항·응답 수·작성자는 없다. www의 «지금 지원할 수 있는 것» 카드가 링크
 * (/f/{formKey})와 마감을 그리는 데 필요한 것이 그 셋이다. PublicFormMetaResponse(OG 카드)와
 * 다른 record인 것은 그쪽이 시간에 따라 변하는 값을 일부러 빼기 때문이다(메신저 캐시) — 이쪽은
 * CDN 5분 캐시 뒤 갱신되는 목록이라 마감을 실어도 된다.
 *
 * 숫자 id(formId)를 싣지 않는다 — 공개 주소는 키로만 만든다(ADR-0036).
 */
public record PublicOpenFormResponse(UUID formKey, String formTtlNm, OffsetDateTime rcptEndDt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static PublicOpenFormResponse of(FormEntity form) {
        return new PublicOpenFormResponse(
                form.getFormKey(), form.getTitle(), toOffsetDateTime(form.getReceiptEndAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
