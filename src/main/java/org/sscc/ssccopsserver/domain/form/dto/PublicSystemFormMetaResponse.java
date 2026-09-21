package org.sscc.ssccopsserver.domain.form.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

import org.sscc.ssccopsserver.domain.form.code.FormReceiptStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;

/*
 * 익명용 «지정 시스템 폼» 메타 (#520 · GET /public/v1/forms/system/RECRUIT/meta · ssccops#436 ·
 * ADR-0044). www의 모집 페이지(/join)가 «지원하기» CTA를 그리는 데 필요한 것 — 링크를 만들 폼 키,
 * 제목, 그리고 **접수 상태와 기간** — 다섯뿐이다.
 *
 * PublicFormMetaResponse(OG 카드)와 갈리는 지점이 receiptStatus·기간이다. 그쪽은 메신저가 카드를
 * 한 번 캐싱하면 갱신하지 않아 시간에 따라 변하는 값을 일부러 뺐는데(ssccops#194), 이쪽은 CDN
 * 5분 캐시(PublicCacheControl) 뒤 갱신되는 **페이지**라 «지금 접수 중인가»를 실어도 마감 뒤에
 * «모집 중»이라 말하는 화면이 남지 않는다 — PublicOpenFormResponse가 마감을 싣는 것과 같은 판단이다.
 * receiptStatus는 FormReceiptPolicy가 계산한 파생값이라 웹이 상태·기간으로 다시 계산하지 않는다.
 *
 * 숫자 id(formId)·문항·응답 수·작성자는 없다 — 공개 주소는 키로만 만들고(ADR-0036) 문항은 인증이
 * 필요한 응답자용 조회의 몫이다. PublicContentDtoContractTest가 이 다섯을 못 박는다.
 */
public record PublicSystemFormMetaResponse(
        UUID formKey,
        String formTtlNm,
        FormReceiptStatus receiptStatus,
        OffsetDateTime rcptBgngDt,
        OffsetDateTime rcptEndDt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static PublicSystemFormMetaResponse of(
            FormEntity form, FormReceiptStatus receiptStatus) {
        return new PublicSystemFormMetaResponse(
                form.getFormKey(),
                form.getTitle(),
                receiptStatus,
                toOffsetDateTime(form.getReceiptBeginAt()),
                toOffsetDateTime(form.getReceiptEndAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
