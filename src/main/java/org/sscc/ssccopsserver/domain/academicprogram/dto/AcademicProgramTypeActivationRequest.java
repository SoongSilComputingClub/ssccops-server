package org.sscc.ssccopsserver.domain.academicprogram.dto;

import jakarta.validation.constraints.NotNull;

/*
 * 학술 활동 유형 사용 여부 전환 요청 (#130 · PATCH /v1/academic-program-types/{typeCd}/activation).
 *
 * 저장 폼과 엔드포인트를 나눈 것은 목록의 토글만 누르는 흐름이 폼 값 전체를 들고 있지 않기
 * 때문이다(SubWorkTypeActivationRequest와 같은 이유).
 */
public record AcademicProgramTypeActivationRequest(@NotNull Boolean useYn) {}
