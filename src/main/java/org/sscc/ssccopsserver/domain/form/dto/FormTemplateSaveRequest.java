package org.sscc.ssccopsserver.domain.form.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.sscc.ssccopsserver.domain.form.entity.QuestionCompositionContent;

/*
 * 폼 템플릿 생성·수정 공용 요청 (#142 · POST /v1/form-templates · PUT /v1/form-templates/{id}).
 *
 * 두 요청이 같은 DTO를 쓰는 것은 FormSaveRequest와 같은 이유다 — 화면 하나(템플릿 편집기)가
 * 신규와 수정에 같은 본문을 만드는데 DTO를 나누면 한쪽에만 필드가 늘어 조용히 어긋난다.
 *
 * useYn이 여기에 없는 것은 의도된 것이다. 생성은 항상 활성이고(만들자마자 비활성인 템플릿은
 * 쓸모가 없다), 수정은 사용 여부를 건드리지 않는다 — 전환은 PATCH .../use 하나로 좁힌다.
 * 폼 수정(PUT)이 formSttsCd를 무시하는 것과 같은 갈래이며, 여기서는 아예 필드를 두지 않았다.
 * 폼 쪽이 필드를 남겨 둔 것은 자동 저장이 상세 응답을 그대로 되돌려 보내기 때문인데
 * 템플릿에는 그런 자동 저장이 없다.
 *
 * creatrMbrId도 없다. 생성자는 인증 주체에서 서버가 채우며, 지정할 수 있게 하면 남의 이름으로
 * 템플릿을 만들 수 있다 (폼과 같다).
 *
 * qitemCpstCn에 @Valid를 걸지 않은 것도 폼과 같은 이유다 — 문항 간 상호 규칙은 필드 단위
 * 제약으로 표현할 수 없어 QuestionCompositionValidator 한 곳에서 검사하고
 * INVALID_QUESTION_COMPOSITION으로 내린다. 폼과 **같은 검증기**를 쓰는 것이 이 이슈의 핵심
 * 제약이다 — 규칙을 한 벌 더 적으면 템플릿으로 만든 폼이 저장에서 거절되는 상태가 생긴다.
 */
public record FormTemplateSaveRequest(
        @NotBlank @Size(max = 200) String tmplNm,
        @Size(max = 500) String tmplExpln,
        @NotNull QuestionCompositionContent qitemCpstCn) {}
