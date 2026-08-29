package org.sscc.ssccopsserver.domain.form.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

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
 * ── alreadySubmitted의 뜻이 좁아졌다 (#143) ──────────────────
 * **"냈는가"가 아니라 "더 낼 수 없는가"다.** 다중 응답을 허용하는 폼에서는 이미 낸 뒤에도 또
 * 내는 것이 정상이므로 화면이 작성 폼을 계속 보여줘야 하는데, 예전 뜻대로면 첫 제출 직후부터
 * 제출 내역 화면이 뜬다. 필드 이름을 바꾸지 않은 것은 단일 응답 폼(지금 있는 폼 전부)에서 두 뜻이
 * 완전히 같아 웹이 이미 쓰는 자리를 깨뜨릴 이유가 없기 때문이다 — 다중 응답 폼에서만 갈린다.
 *
 * 그 대신 mltplRspnsYn(이 폼이 여러 건을 받는가)과 myResponseCount(내가 낸 건수)를 함께 내려
 * 화면이 "이미 2건 제출했고 더 낼 수 있다"를 그릴 수 있게 한다. submittedAt은 **마지막** 제출
 * 일시이며, 다중 응답 폼에서는 alreadySubmitted가 false인데도 값이 있을 수 있다 — 두 필드가
 * 묻는 것이 다르기 때문이다(하나는 지금 낼 수 있는가, 다른 하나는 마지막으로 언제 냈는가).
 * 건별 상태는 이 응답이 아니라 GET /v1/forms/{formId}/responses/mine이 준다.
 *
 * ── 반려된 응답은 alreadySubmitted를 세우지 않는다 (#192) ──────
 * 그 뜻을 "더 낼 수 없는가"로 좁힌 이상 반려는 여기 들 수 없다 — 반려는 그 응답에 대한 종결이지
 * 그 폼에 대한 종결이 아니고(#141), 되돌리는 길이 새 응답이라 단일 응답 폼에서도 다시 낼 수
 * 있어야 한다. 그전에는 낸 응답이 하나라도 있으면 상태를 보지 않고 참이라, 반려된 신청자에게
 * 작성 화면 대신 제출 내역 화면이 영구히 떴다(막는 쪽인 제출·새 초안 판정도 같이 잠겨 있어
 * 화면과 API가 어긋나지는 않았지만, 둘 다 틀린 답을 하고 있었다).
 *
 * myResponseCount·submittedAt은 그대로 반려된 응답을 포함한다 — 그 둘이 묻는 것은 "냈는가"이고
 * 반려된 응답도 낸 것이 맞다. 셋 중 alreadySubmitted만 기준이 다르다.
 *
 * 일시는 AP-12에 따라 Asia/Seoul 오프셋을 포함해 내려준다.
 */
public record PublicFormResponse(
        Long formId,
        String formTtlNm,
        OffsetDateTime rcptBgngDt,
        OffsetDateTime rcptEndDt,
        QuestionCompositionContent qitemCpstCn,
        boolean mltplRspnsYn,
        boolean alreadySubmitted,
        int myResponseCount,
        OffsetDateTime submittedAt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    /*
     * submittedResponses는 이 회원이 이 폼에 **낸** 응답들이다(임시저장은 낸 것이 아니라 빠져
     * 있으며, 거르는 자리는 서비스다 — 목록·집계와 같은 기준을 써야 한다).
     *
     * 마지막 원소를 쓰는 것은 순번 오름차순으로 오기 때문이다.
     *
     * "더 낼 수 없는가"는 건수가 아니라 상태로 판정한다 (#192). 상태별 판정은 엔티티에 물으며
     * (blocksNewResponse) 여기서 어떤 상태가 막는지 나열하지 않는다 — 제출 경로가 같은 사실을
     * 물어야 하고, 두 곳이 각자 상태를 비교하면 화면과 API가 갈린다.
     */
    public static PublicFormResponse of(
            FormEntity form, List<FormResponseHistoryEntity> submittedResponses) {

        FormResponseHistoryEntity latest =
                submittedResponses.isEmpty()
                        ? null
                        : submittedResponses.get(submittedResponses.size() - 1);

        boolean alreadySubmitted =
                !form.isMultipleResponseAllowed()
                        && submittedResponses.stream()
                                .anyMatch(FormResponseHistoryEntity::blocksNewResponse);

        return new PublicFormResponse(
                form.getId(),
                form.getTitle(),
                toOffsetDateTime(form.getReceiptBeginAt()),
                toOffsetDateTime(form.getReceiptEndAt()),
                form.getQuestionComposition(),
                form.isMultipleResponseAllowed(),
                alreadySubmitted,
                submittedResponses.size(),
                latest == null ? null : toOffsetDateTime(latest.getSubmittedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
