package org.sscc.ssccopsserver.domain.form.dto;

import java.time.OffsetDateTime;

import org.sscc.ssccopsserver.domain.form.entity.FormTemplateEntity;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;

/*
 * 폼 템플릿 단건 상세 (#142 · GET /v1/form-templates/{formTmplId}).
 *
 * 목록(FormTemplateResponse)과 필드가 같고 qitemCpstCn 하나만 더 있다. 상속이나 중첩 대신
 * 필드를 다시 적은 것은 record가 상속을 지원하지 않아서이기도 하지만, 무엇보다 "목록은 문항
 * 구성을 싣지 않는다"는 규칙이 두 record의 필드 목록 차이로 눈에 보이게 하기 위해서다.
 *
 * 템플릿 편집 화면은 이 응답을 그대로 초안으로 받아 고친 뒤 PUT으로 돌려보낸다. 그래서
 * 요청 DTO(FormTemplateSaveRequest)와 필드 이름이 일치해야 한다 — 폼 상세·폼 편집이 맺고
 * 있는 관계와 같다.
 */
public record FormTemplateDetailResponse(
        Long formTmplId,
        String tmplNm,
        String tmplExpln,
        boolean useYn,
        int qitemCnt,
        QuestionCompositionContent qitemCpstCn,
        Long creatrMbrId,
        String creatrMbrNm,
        OffsetDateTime crtDt,
        OffsetDateTime mdfcnDt) {

    public static FormTemplateDetailResponse from(FormTemplateEntity template) {
        return new FormTemplateDetailResponse(
                template.getId(),
                template.getName(),
                template.getDescription(),
                template.isActive(),
                FormTemplateResponse.questionCountOf(template),
                template.getQuestionComposition(),
                template.getCreator().getId(),
                template.getCreator().getName(),
                FormTemplateResponse.toOffsetDateTime(template.getCreatedAt()),
                FormTemplateResponse.toOffsetDateTime(template.getUpdatedAt()));
    }
}
