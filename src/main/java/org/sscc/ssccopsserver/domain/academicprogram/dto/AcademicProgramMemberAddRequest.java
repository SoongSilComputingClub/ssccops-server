package org.sscc.ssccopsserver.domain.academicprogram.dto;

import jakarta.validation.constraints.NotNull;

/*
 * 팀원 추가 요청 (#612 · POST /v1/academic-programs/{academicProgramId}/members).
 *
 * 받는 것은 회원 하나뿐이다 — **신청서 없이** 합류한다(2026-09-30 결정 · 늦게 합류하는 사람에게
 * 마감된 폼부터 내게 하지 않는다). 그래서 참가 행의 form_rspns_id는 비고, 상태는 언제나 확정이다 —
 * 대기로 넣을 이유가 없고(정원이 참고치다), 대기로 옮기는 것은 PATCH가 한다.
 *
 * 수행자(등록 처리자)는 요청이 아니라 인증 주체에서 온다(#78이 세운 규칙).
 */
public record AcademicProgramMemberAddRequest(@NotNull Long mbrId) {}
