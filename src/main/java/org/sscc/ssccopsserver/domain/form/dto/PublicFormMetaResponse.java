package org.sscc.ssccopsserver.domain.form.dto;

import java.util.List;

import org.sscc.ssccopsserver.domain.form.entity.FormEntity;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;
import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent.Page;

/*
 * 익명 미리보기용 폼 메타 (ssccops#201). 메신저 크롤러가 공개 폼 링크(/f/{formId})의 OG 카드를
 * 만들 때 쓰는 값이며, **제목과 첫 페이지 안내 문구뿐이다.**
 *
 * 접수 기간·접수 상태·문항 구성을 싣지 않는 것은 계약이다. 메신저는 OG를 한 번 캐싱하면
 * 갱신하지 않아 카드가 굳는다 — 접수 상태를 넣으면 마감된 뒤에도 "모집 중"이라 말하는 카드가
 * 방에 남는다(ssccops#194 제약 ②). 문항은 인증이 필요한 공개 폼 조회(PublicFormResponse)의
 * 몫이고, 익명에게는 나가지 않는다.
 *
 * 안내 문구는 폼에 전용 컬럼이 없어 문항 구성의 **첫 페이지 설명(pageDescCn)**을 쓴다 —
 * 응답자가 링크를 열었을 때 제목 아래에서 처음 읽는 문장이 그것이다. 이름을 새로 짓지 않고
 * JSON 키를 그대로 둔 것은 이 값이 파생이 아니라 그 키의 값 그대로라는 뜻이다. 마크다운을
 * 걷어 한 줄로 줄이는 것은 카드를 만드는 웹의 몫이다(apps/www 행사 OG와 같은 자리).
 */
public record PublicFormMetaResponse(Long formId, String formTtlNm, String pageDescCn) {

    public static PublicFormMetaResponse of(FormEntity form) {
        return new PublicFormMetaResponse(
                form.getId(), form.getTitle(), firstPageDescription(form.getQuestionComposition()));
    }

    /* 첫 페이지가 없거나 설명이 비어 있으면 null — 서버가 대체 문구를 만들어 내지 않는다 */
    private static String firstPageDescription(QuestionCompositionContent composition) {
        if (composition == null || composition.pages() == null) {
            return null;
        }
        List<Page> pages = composition.pages();
        if (pages.isEmpty() || pages.get(0) == null) {
            return null;
        }
        String description = pages.get(0).pageDescCn();
        return description == null || description.isBlank() ? null : description;
    }
}
