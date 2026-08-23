package org.sscc.ssccopsserver.domain.academicprogram.dto;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.NotNull;

import org.sscc.ssccopsserver.domain.academicprogram.entity.AcademicProgramTransition;

/*
 * 학술 활동 상태 전이 요청 (#133 · POST /v1/academic-programs/{academicProgramId}/transitions).
 *
 * 다음 상태(sttsCd)가 아니라 전이 액션을 받는다 — SubWorkTransitionRequest·
 * FormStatusChangeRequest와 같은 선례다. 수행자는 요청 본문이 아니라 인증 주체(@CurrentMember)
 * 에서 온다.
 *
 * recruitmentStartDt·recruitmentEndDt는 START_RECRUITMENT일 때만 쓰인다. APPROVE_COMPLETION에
 * 실려 와도 무시한다 — 값 자체가 그 전이와 무관하므로 거절보다는 무시가 맞다(FormController가
 * 자동 저장 본문의 formSttsCd를 무시하는 것과 같은 태도이되, 여기는 매 타이핑이 아니라 버튼
 * 클릭 한 번이라 되돌릴 자동 저장 흐름이 없다는 차이는 있다).
 *
 * 기간 역전(rcptEndDt < rcptBgngDt) 검증은 Bean Validation이 아니라 FormEntity가 한다 —
 * 폼 도메인의 INVALID_RECEIPT_PERIOD를 그대로 전파해야 계약표와 어긋나지 않는다.
 */
public record AcademicProgramTransitionRequest(
        @NotNull AcademicProgramTransition transition,
        OffsetDateTime recruitmentStartDt,
        OffsetDateTime recruitmentEndDt) {}
