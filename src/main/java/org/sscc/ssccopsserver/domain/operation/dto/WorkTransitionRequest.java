package org.sscc.ssccopsserver.domain.operation.dto;

import jakarta.validation.constraints.NotNull;

import org.sscc.ssccopsserver.domain.operation.entity.WorkTransitionAction;

/*
 * 상위 업무 상태 전이 요청 (#622 · POST /v1/works/{workId}/transitions).
 *
 * 수행자는 요청 본문이 아니라 인증 주체에서 온다 (LY-05). 전이 후 상태도 받지 않는다 —
 * 다음 상태는 전이표가 정하는 것이지 클라이언트가 고르는 값이 아니다(SubWorkTransitionRequest와
 * 같은 판단).
 *
 * 사유(reason)가 없는 것은 의도된 것이다. 하위 업무와 달리 사유를 요구하는 전이가 없고, 상태
 * 이력 표도 두지 않아(ssccops#563 · 감사 로그로 충분) 받아도 남길 자리가 없다 — 감사 로그는 사람이
 * 쓴 문장을 싣지 않는다(ADR-0024).
 */
public record WorkTransitionRequest(@NotNull WorkTransitionAction transition) {}
