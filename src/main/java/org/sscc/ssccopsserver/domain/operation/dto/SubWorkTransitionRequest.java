package org.sscc.ssccopsserver.domain.operation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.sscc.ssccopsserver.domain.operation.entity.TransitionAction;

/*
 * 하위 업무 상태 전이 요청 (OPS-010 · POST /v1/sub-works/{id}/transitions).
 *
 * 수행자는 요청 본문이 아니라 인증 주체에서 온다 (LY-05). 전이 후 상태도 받지 않는다 —
 * 다음 상태는 전이표가 정하는 것이지 클라이언트가 고르는 값이 아니다.
 *
 * reason에 @NotBlank를 걸지 않은 것은 의도된 것이다. 사유가 필수인 전이는 반려뿐이고,
 * Bean Validation으로 잡으면 전역 핸들러가 VALIDATION_FAILED(400)로 바꿔 버린다.
 * 정의서가 요구하는 코드는 REASON_REQUIRED(422)라, 필수 여부는 전이 메서드가 판단한다.
 */
public record SubWorkTransitionRequest(
        @NotNull TransitionAction transition, @Size(max = 500) String reason) {}
