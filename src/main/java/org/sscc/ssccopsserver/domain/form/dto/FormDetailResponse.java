package org.sscc.ssccopsserver.domain.form.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import org.sscc.ssccopsserver.domain.form.code.FormReceiptStatus;
import org.sscc.ssccopsserver.domain.form.code.FormStatus;
import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;

/*
 * 폼 단건 상세 (#32 · GET /v1/forms/{formId}). 폼 상세 화면과 폼 편집 화면이 같이 쓴다 —
 * 편집기는 이 응답을 그대로 초안(draft)으로 받아 고친 뒤 PUT으로 돌려보낸다. 그래서 요청
 * DTO(FormSaveRequest)와 필드 이름이 일치해야 하고, 문항 구성을 통째로 싣는다.
 *
 * 생성자를 중첩 객체가 아니라 creatrMbrId·creatrMbrNm 두 필드로 편 것은 웹 타입의 Form이
 * creatrMbrId만 갖고 이름은 회원 스토어에서 따로 찾고 있어서다. 서버가 이름까지 같이 내리면
 * 그 조회가 사라지고, 식별자는 그대로 남아 있어 기존 코드가 깨지지 않는다.
 *
 * responseCount는 목록과 같은 기준(제출 이상만)이다. 상세 화면은 그 위에 '전체 · 제출 · 승인 ·
 * 반려' 네 숫자를 보여주므로 responseSummary를 함께 내린다 (#37) — 두 값은 같은 집계에서 나오고
 * responseSummary.total과 responseCount는 언제나 같다. 굳이 둘 다 두는 것은 목록(FormSummary)이
 * 이미 responseCount를 쓰고 있어 상세만 이름을 바꾸면 웹이 두 응답을 다르게 읽어야 하기 때문이다.
 *
 * 예전에는 상태별 집계를 응답 조회 API(#37)가 따로 갖는 것으로 미뤄 뒀는데, 그동안 웹은
 * res.responseSummary를 찾지 못해 요약 상자를 늘 0으로 그렸다 — 터지지 않고 조용히 틀리는
 * 종류라 화면만 보고는 알 수 없었다. DRAFT는 네 숫자 어디에도 들어가지 않는다 (#36 규칙).
 *
 * receiptStatus는 목록과 같은 파생 값이다 (#33 · FormReceiptPolicy). 상세 화면의 '접수 시작 /
 * 마감' 버튼 문구는 formSttsCd로 고르지만(전이표가 그 값으로 정의돼 있다), 사용자에게 보이는
 * 배지는 기간까지 반영해야 하므로 두 값을 함께 내린다.
 *
 * 일시는 AP-12에 따라 Asia/Seoul 오프셋을 포함해 내려준다.
 */
public record FormDetailResponse(
        Long formId,
        String formTtlNm,
        FormStatus formSttsCd,
        FormReceiptStatus receiptStatus,
        OffsetDateTime rcptBgngDt,
        OffsetDateTime rcptEndDt,
        QuestionCompositionContent qitemCpstCn,
        List<FormLabelSummaryResponse> labels,
        long responseCount,
        FormResponseStatusSummary responseSummary,
        Long creatrMbrId,
        String creatrMbrNm,
        OffsetDateTime crtDt,
        OffsetDateTime mdfcnDt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static FormDetailResponse of(
            FormEntity form,
            FormReceiptStatus receiptStatus,
            List<FormLabelSummaryResponse> labels,
            FormResponseStatusSummary responseSummary) {
        return new FormDetailResponse(
                form.getId(),
                form.getTitle(),
                form.getStatus(),
                receiptStatus,
                toOffsetDateTime(form.getReceiptBeginAt()),
                toOffsetDateTime(form.getReceiptEndAt()),
                form.getQuestionComposition(),
                labels,
                responseSummary.total(),
                responseSummary,
                form.getCreator().getId(),
                form.getCreator().getName(),
                toOffsetDateTime(form.getCreatedAt()),
                toOffsetDateTime(form.getUpdatedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
