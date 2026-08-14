package org.sscc.ssccopsserver.domain.form.dto;

import jakarta.validation.constraints.NotNull;

import org.sscc.ssccopsserver.domain.form.code.ResponseStatus;

/*
 * 응답 상태 변경 요청 (#37 · PATCH /v1/forms/{formId}/responses/{formRspnsId}/status).
 *
 * 폼 상태 전이(FormStatusChangeRequest)와 달리 액션이 아니라 **대상 상태를 그대로 받는다.**
 * 두 전이의 모양이 다르기 때문이다 — 폼은 DRAFT→OPEN→CLOSED로 한 방향이라 '연다/닫는다'는
 * 액션이 다음 상태를 결정하지만, 응답 심사는 SUBMITTED·ACCEPTED·REJECTED 사이를 자유롭게
 * 오가므로 액션과 상태가 1:1이 되어 액션 어휘가 상태 어휘를 그대로 베낀 것이 된다.
 *
 * 기준 코드 밖의 값은 여기까지 오지 않는다. enum 역직렬화 실패를 전역 핸들러가 400
 * INVALID_CODE_VALUE로 옮긴다 (VL-09).
 *
 * 수행자는 본문이 아니라 인증 주체에서 온다 (LY-05). 다만 응답 상태 이력 테이블이 없어 그 값이
 * 어디에도 남지 않는다 — "누가 승인했는지"는 감사 로그(#8)가 확정되기 전까지 기록되지 않는다.
 */
public record FormResponseStatusChangeRequest(@NotNull ResponseStatus rspnsSttsCd) {}
