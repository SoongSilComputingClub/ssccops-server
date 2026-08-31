package org.sscc.ssccopsserver.domain.member.dto;

/*
 * 기수 계산 결과 (GET /v1/members/generation, #205).
 *
 * 숫자 하나를 record로 감싸는 것은 응답 봉투(ApiResponse)의 data가 객체여야 화면이 나중에
 * 필드를 더 받을 수 있기 때문이다 — 지금 42만 내리면 "이 값이 기수인가"를 문서로만 알 수 있고,
 * 근거(연도)나 안내 문구를 더할 자리가 없다.
 *
 * **계산 결과일 뿐 저장된 값이 아니다.** gen_no에 넣는 것은 운영진이 화면에서 확인한 뒤이며,
 * 이 응답 자체는 회원을 가리키지 않는다 (BR-M43 · GenerationPolicy 주석).
 */
public record MemberGenerationResponse(int generationNumber) {}
