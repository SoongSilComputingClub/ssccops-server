package org.sscc.ssccopsserver.domain.form.dto;

import jakarta.validation.constraints.Size;

/*
 * 템플릿으로 새 폼 만들기 요청 (#142 · POST /v1/form-templates/{formTmplId}/forms).
 *
 * 필드가 하나뿐이고 그마저 선택이라 본문 자체를 생략할 수 있다(컨트롤러가
 * @RequestBody(required = false)로 받는다). 생략하면 폼 제목은 템플릿명이 된다.
 *
 * 제목을 받는 자리를 둔 것은 복제(POST /v1/forms/{formId}/duplicate)와 갈리는 지점이다.
 * 복제는 "이 폼과 똑같은 것 하나 더"라 제목이 원본에서 파생되는 것이 옳지만(그래서 '(복사본)'),
 * 템플릿은 "2026 신규모집 표준 문항"처럼 폼 제목으로 쓰기에 부적절한 이름을 갖는 것이 정상이다.
 * 만들고 나서 PUT으로 제목을 한 번 더 고치게 하면, 그 두 번째 호출이 실패했을 때 사용자가
 * 의도하지 않은 이름의 폼이 남는다 — 폼 생성이 상태(OPEN)를 함께 받는 것과 같은 판단이다.
 *
 * 접수 기간·라벨·상태는 받지 않는다. 템플릿에서 나온 폼은 언제나 DRAFT이고 접수 기간과 라벨이
 * 비어 있다 — 이 요청에 그 값들을 실을 수 있게 하면 '템플릿으로 만들기'가 '폼 생성'의 또 다른
 * 입구가 되고, 폼을 만드는 규칙이 두 곳에 놓인다. 기간과 라벨은 만들어진 폼을 PUT으로 채운다.
 */
public record FormFromTemplateRequest(@Size(max = 200) String formTtlNm) {

    /*
     * 본문이나 필드가 비어 있으면 템플릿명을 그대로 쓴다. 공백만 채워 보낸 경우도 같이 본다 —
     * 제목이 "   "인 폼은 목록에서 이름 없는 줄로 보이므로, 굳이 400으로 되돌리기보다
     * 생략과 같게 다루는 편이 화면에서 복구 가능한 상태를 남긴다.
     */
    public static String titleOrDefault(FormFromTemplateRequest request, String templateName) {
        if (request == null || request.formTtlNm() == null || request.formTtlNm().isBlank()) {
            return templateName;
        }
        return request.formTtlNm();
    }
}
