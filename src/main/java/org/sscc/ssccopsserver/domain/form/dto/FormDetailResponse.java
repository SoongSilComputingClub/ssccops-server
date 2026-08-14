package org.sscc.ssccopsserver.domain.form.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

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
 * responseCount는 목록과 같은 기준(제출 이상만)이다. 상세 화면의 '응답 요약'은 상태별로
 * 나눠 보여주는데 그 집계는 응답 조회 API(#37)의 몫이고, 여기서는 목록과 같은 한 숫자만 준다 —
 * 같은 이름의 필드가 화면마다 다른 값을 뜻하면 어느 쪽이 맞는지 알 수 없게 된다.
 *
 * 일시는 AP-12에 따라 Asia/Seoul 오프셋을 포함해 내려준다.
 */
public record FormDetailResponse(
        Long formId,
        String formTtlNm,
        FormStatus formSttsCd,
        OffsetDateTime rcptBgngDt,
        OffsetDateTime rcptEndDt,
        QuestionCompositionContent qitemCpstCn,
        List<FormLabelSummaryResponse> labels,
        long responseCount,
        Long creatrMbrId,
        String creatrMbrNm,
        OffsetDateTime crtDt,
        OffsetDateTime mdfcnDt) {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    public static FormDetailResponse of(
            FormEntity form, List<FormLabelSummaryResponse> labels, long responseCount) {
        return new FormDetailResponse(
                form.getId(),
                form.getTitle(),
                form.getStatus(),
                toOffsetDateTime(form.getReceiptBeginAt()),
                toOffsetDateTime(form.getReceiptEndAt()),
                form.getQuestionComposition(),
                labels,
                responseCount,
                form.getCreator().getId(),
                form.getCreator().getName(),
                toOffsetDateTime(form.getCreatedAt()),
                toOffsetDateTime(form.getUpdatedAt()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atZone(SERVICE_ZONE).toOffsetDateTime();
    }
}
