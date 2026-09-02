package org.sscc.ssccopsserver.domain.form.dto;

import jakarta.validation.constraints.Size;

/*
 * 이 폼의 문항 구성을 템플릿으로 저장 요청 (#142 · POST /v1/forms/{formId}/templates).
 *
 * 두 필드 모두 선택이라 본문 자체를 생략할 수 있다(컨트롤러가 @RequestBody(required = false)로
 * 받는다). 생략하면 템플릿명은 폼 제목이 된다.
 *
 * qitemCpstCn을 받지 않는 것이 이 요청의 요점이다. 저장 대상은 **경로가 가리키는 폼의 현재
 * 문항 구성**이며 클라이언트가 실어 보낸 값이 아니다 — 받을 수 있게 하면 "이 폼을 템플릿으로"가
 * 아니라 그냥 템플릿 생성 API가 하나 더 생기는 것이고, 화면이 들고 있던 낡은 초안이 폼과 다른
 * 내용의 템플릿으로 굳는다.
 *
 * useYn도 받지 않는다. 새 템플릿은 언제나 활성이다 (POST /v1/form-templates와 같다).
 */
public record TemplateFromFormRequest(
        @Size(max = 200) String tmplNm, @Size(max = 500) String tmplExpln) {

    /** 템플릿명 생략은 폼 제목을 쓴다. 공백만 보낸 경우도 같이 본다 (FormFromTemplateRequest와 같은 규칙) */
    public static String nameOrDefault(TemplateFromFormRequest request, String formTitle) {
        if (request == null || request.tmplNm() == null || request.tmplNm().isBlank()) {
            return formTitle;
        }
        return request.tmplNm();
    }

    public static String descriptionOf(TemplateFromFormRequest request) {
        return request == null ? null : request.tmplExpln();
    }
}
