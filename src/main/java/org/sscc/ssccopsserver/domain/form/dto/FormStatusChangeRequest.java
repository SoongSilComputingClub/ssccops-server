package org.sscc.ssccopsserver.domain.form.dto;

import jakarta.validation.constraints.NotNull;

import org.sscc.ssccopsserver.domain.form.code.FormStatusAction;

/*
 * 폼 접수 상태 전이 요청 (#33 · POST /v1/forms/{formId}/status).
 *
 * 다음 상태(formSttsCd)가 아니라 액션을 받는다. 다음 상태를 받으면 클라이언트가 전이표를
 * 들고 있어야 하고, 표가 바뀔 때 웹과 서버가 따로 바뀌어 어긋난다 — 어느 상태로 가는지는
 * FormStatusAction이 정한다 (SubWorkTransitionRequest 선례).
 *
 * 수행자는 요청 본문이 아니라 인증 주체에서 온다 (LY-05).
 *
 * 전이 가능 여부를 @AssertTrue 같은 Bean Validation으로 잡지 않는 것은 의도된 것이다.
 * 현재 상태를 알아야 판단할 수 있고, Bean Validation 실패는 전역 핸들러가
 * VALIDATION_FAILED로 바꿔 버려 계약표의 INVALID_FORM_STATUS_TRANSITION과 어긋난다.
 */
public record FormStatusChangeRequest(@NotNull FormStatusAction action) {}
