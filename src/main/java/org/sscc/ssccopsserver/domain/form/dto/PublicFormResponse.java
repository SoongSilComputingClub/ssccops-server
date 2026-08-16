package org.sscc.ssccopsserver.domain.form.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.FormResponseHistoryEntity;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;

/*
 * 응답자용 폼 조회 (#35 · GET /v1/forms/{formId}/public).
 *
 * 운영자용 상세(FormDetailResponse)와 일부러 스키마를 나눈다. 응답자에게 생성자(creatrMbrId)·
 * 응답 집계(responseCount)·폼 상태 내부값(formSttsCd)을 줄 이유가 없고, 하나로 합치면 운영자용
 * 필드가 늘 때마다 공개 링크로 새어 나갈 것이 함께 늘어난다. 같은 화면을 두 응답이 그리지
 * 않으므로 필드가 어긋날 걱정도 없다.
 *
 * 접수 가능하지 않은 폼은 애초에 이 DTO까지 오지 않는다 — 409 FORM_NOT_ACCEPTING으로 끊긴다.
 * 그래서 문항 구성(qitemCpstCn)이 이 응답에 실려 있다는 것 자체가 "지금 답을 낼 수 있다"는 뜻이다.
 *
 * alreadySubmitted·submittedAt은 재제출을 막기 위한 값이 아니라(그건 제출 API가 막는다) 웹이
 * 작성 화면 대신 제출 내역 화면을 고르기 위한 값이다.
 *
 * 일시는 AP-12에 따라 Asia/Seoul 오프셋을 포함해 내려준다.
 */
public record PublicFormResponse(
        Long formId,
        String formTtlNm,
        OffsetDateTime rcptBgngDt,
        OffsetDateTime rcptEndDt,
        QuestionCompositionContent qitemCpstCn,
        boolean alreadySubmitted,
        OffsetDateTime submittedAt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    /** submitted가 null이면 아직 제출하지 않은 것이다 (임시저장 응답도 제출로 치지 않는다) */
    public static PublicFormResponse of(FormEntity form, FormResponseHistoryEntity submitted) {
        return new PublicFormResponse(
                form.getId(),
                form.getTitle(),
                toOffsetDateTime(form.getReceiptBeginAt()),
                toOffsetDateTime(form.getReceiptEndAt()),
                form.getQuestionComposition(),
                submitted != null,
                submitted == null ? null : toOffsetDateTime(submitted.getSubmittedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
