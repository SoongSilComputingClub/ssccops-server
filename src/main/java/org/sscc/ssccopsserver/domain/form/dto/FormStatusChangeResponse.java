package org.sscc.ssccopsserver.domain.form.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.form.code.FormReceiptStatus;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;

/*
 * 폼 접수 상태 전이 응답 (#33 · POST /v1/forms/{formId}/status).
 *
 * 접수 기간을 함께 싣는 것은 화면이 상태 배지와 기간 문구를 같이 그리기 때문이다. 상태만
 * 돌려주면 프론트가 전이 후 상세를 한 번 더 조회하게 된다.
 *
 * receiptStatus는 저장된 값이 아니라 formSttsCd와 접수 기간을 함께 본 파생 값이다
 * (FormReceiptPolicy). OPEN으로 열었는데 시작 일시가 아직 오지 않았다면 formSttsCd는 OPEN이지만
 * receiptStatus는 SCHEDULED다 — 버튼을 누른 직후 화면이 '접수 중'이라 잘못 말하지 않게 한다.
 *
 * 일시는 AP-12에 따라 Asia/Seoul 오프셋을 포함해 내려준다.
 */
public record FormStatusChangeResponse(
        Long formId,
        FormStatus formSttsCd,
        FormReceiptStatus receiptStatus,
        OffsetDateTime rcptBgngDt,
        OffsetDateTime rcptEndDt,
        OffsetDateTime mdfcnDt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static FormStatusChangeResponse of(FormEntity form, FormReceiptStatus receiptStatus) {
        return new FormStatusChangeResponse(
                form.getId(),
                form.getStatus(),
                receiptStatus,
                toOffsetDateTime(form.getReceiptBeginAt()),
                toOffsetDateTime(form.getReceiptEndAt()),
                toOffsetDateTime(form.getUpdatedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
