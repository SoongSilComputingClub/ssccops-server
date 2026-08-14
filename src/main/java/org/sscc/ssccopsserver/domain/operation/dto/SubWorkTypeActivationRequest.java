package org.sscc.ssccopsserver.domain.operation.dto;

import jakarta.validation.constraints.NotNull;

/*
 * 하위 업무 유형 사용 여부 전환 요청 (OPS-019 · PATCH /v1/sub-work-types/{id}/activation).
 *
 * 저장 폼과 엔드포인트를 나눈 것은 목록의 토글만 누르는 흐름이 폼 값 전체를 들고 있지 않기
 * 때문이다. 토글 한 번에 유형 전체를 다시 보내게 하면, 화면이 들고 있지 않은 값이 함께
 * 덮어써진다.
 */
public record SubWorkTypeActivationRequest(@NotNull Boolean useYn) {}
