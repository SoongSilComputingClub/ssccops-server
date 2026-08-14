package org.sscc.ssccopsserver.domain.form.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;

/*
 * 폼 목록 항목 (#32 · GET /v1/forms). 폼 관리 화면의 카드 한 장이 쓰는 값만 담는다.
 *
 * qitemCpstCn이 없는 것이 이 DTO의 핵심이다. 문항은 폼 하나에 수십 개까지 늘어나는데
 * 목록 카드는 제목·상태·기간·라벨·응답 수만 그린다. 상세와 같은 모양으로 내리면 화면에
 * 쓰이지도 않는 JSON이 폼 수만큼 곱해져 목록 응답이 비대해진다 (AP-15의 반대편 — 값이 없어도
 * 필드는 내리되, 쓰지 않는 필드는 애초에 넣지 않는다).
 *
 * responseCount는 제출 이상(SUBMITTED·ACCEPTED·REJECTED)만 센다. 작성 중인 임시저장(DRAFT)은
 * 아직 응답자가 낸 것이 아니라, 세면 운영진이 보는 "응답 N건"이 실제 접수 건수보다 부풀어
 * 마감 판단을 잘못하게 만든다.
 *
 * 일시는 AP-12에 따라 Asia/Seoul 오프셋을 포함해 내려준다.
 */
public record FormSummaryResponse(
        Long formId,
        String formTtlNm,
        FormStatus formSttsCd,
        OffsetDateTime rcptBgngDt,
        OffsetDateTime rcptEndDt,
        List<FormLabelSummaryResponse> labels,
        long responseCount,
        OffsetDateTime mdfcnDt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static FormSummaryResponse of(
            FormEntity form, List<FormLabelSummaryResponse> labels, long responseCount) {
        return new FormSummaryResponse(
                form.getId(),
                form.getTitle(),
                form.getStatus(),
                toOffsetDateTime(form.getReceiptBeginAt()),
                toOffsetDateTime(form.getReceiptEndAt()),
                labels,
                responseCount,
                toOffsetDateTime(form.getUpdatedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
