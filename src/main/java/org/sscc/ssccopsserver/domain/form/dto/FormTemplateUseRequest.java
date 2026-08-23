package org.sscc.ssccopsserver.domain.form.dto;

import jakarta.validation.constraints.NotNull;

/*
 * 폼 템플릿 사용 여부 전환 요청 (#142 · PATCH /v1/form-templates/{formTmplId}/use).
 *
 * Boolean(래퍼)에 @NotNull인 것은 FormLabelUpdateRequest와 같은 이유다 — primitive으로 두면
 * 필드가 빠진 요청이 false로 조용히 해석돼, 켜려던 토글이 템플릿을 내려 버린다.
 */
public record FormTemplateUseRequest(@NotNull Boolean useYn) {}
