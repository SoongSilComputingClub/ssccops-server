package org.sscc.ssccopsserver.domain.operation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.sscc.ssccopsserver.domain.operation.entity.MeetingTransitionAction;

/*
 * 회의 상태 전이 요청 (OPS-026 · POST /v1/meetings/{id}/transitions).
 *
 * 수행자는 요청 본문이 아니라 인증 주체에서 온다(LY-05). reason에 @NotBlank를 걸지 않은 것은
 * 의도된 것이다 — 사유가 필수인 전이는 취소(CANCEL)뿐이고, 필수 여부는 전이 메서드가
 * 판단해야 정의서가 요구하는 REASON_REQUIRED(422)가 나온다(SubWorkTransitionRequest와 같은 이유).
 */
public record MeetingTransitionRequest(
        @NotNull MeetingTransitionAction transition, @Size(max = 500) String reason) {}
