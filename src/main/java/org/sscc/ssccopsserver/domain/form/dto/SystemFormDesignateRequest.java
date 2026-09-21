package org.sscc.ssccopsserver.domain.form.dto;

import jakarta.validation.constraints.NotNull;

/*
 * 시스템 폼 지정 요청 (#520 · PUT /v1/forms/system/{sysFormCd} · ssccops#436 · ADR-0044).
 *
 * 본문에 실리는 것은 «어느 폼으로»(formId)뿐이다. **코드(sysFormCd)는 본문이 아니라 경로에서 온다** —
 * 지정할 수 있는 코드는 서버의 허용 목록(DesignatableSystemForm)이 정하고, 본문으로 코드를 받으면
 * #140이 막아 둔 «요청 본문으로 시스템 폼을 세우는 길»이 열린다. 경로 변수는 자원의 이름이지
 * 값이 아니다.
 *
 * formKey(UUID)가 아니라 formId인 것은 이 요청이 어드민 화면(폼 목록 · FORM_STATUS_CHANGE)에서
 * 오기 때문이다 — 그 화면은 숫자 id로 폼을 다룬다. 공개 주소가 키인 것(ADR-0036)은 익명이 훑는 것을
 * 막기 위해서였고 여기에는 그 조건이 없다.
 */
public record SystemFormDesignateRequest(@NotNull Long formId) {}
