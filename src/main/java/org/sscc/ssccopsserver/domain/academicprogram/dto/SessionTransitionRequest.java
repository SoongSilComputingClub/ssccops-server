package org.sscc.ssccopsserver.domain.academicprogram.dto;

import jakarta.validation.constraints.NotNull;

import org.sscc.ssccopsserver.domain.academicprogram.entity.SessionTransition;

/*
 * 회차 승인·수정요청 요청 (#136 · POST .../sessions/{sessionId}/transitions).
 *
 * 다음 상태(sttsCd)가 아니라 전이 액션을 받는다 — AcademicProgramTransitionRequest·
 * SubWorkTransitionRequest와 같은 선례다. 승인자는 요청 본문이 아니라 인증 주체
 * (@CurrentMember)에서 온다 — 받아 주면 "누가 승인했는가"를 스스로 적어 넣을 수 있어
 * acdm_actv_aprv 행이 증거가 되지 못한다(등급·상태 변경 #78이 세운 규칙).
 *
 * reason에 @NotBlank을 달지 않은 것은 필수 여부가 함께 온 transition에 달려 있기 때문이다 —
 * 승인은 사유가 선택이고 수정요청은 필수라 필드 하나만 보고는 판정할 수 없다. 조건부 검증을
 * Bean Validation으로 표현해도 전역 핸들러가 VALIDATION_FAILED로 뭉개 웹이 "사유를 적으라"는
 * 안내를 고를 수 없다. 실제로 막는 자리는 SessionEntity.changeStatus이며 코드는
 * REVISION_REASON_REQUIRED다(폼 응답 검토 #141과 같은 판단).
 *
 * 기준 코드 밖의 transition은 여기까지 오지 않는다 — enum 역직렬화 실패를 전역 핸들러가
 * 400 INVALID_CODE_VALUE로 옮긴다.
 */
public record SessionTransitionRequest(@NotNull SessionTransition transition, String reason) {}
