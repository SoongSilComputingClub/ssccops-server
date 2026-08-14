package org.sscc.ssccopsserver.domain.form.dto;

import jakarta.validation.constraints.NotNull;

import org.sscc.ssccopsserver.domain.form.entity.ResponseContent;

/*
 * 응답 제출 요청 (#35 · POST /v1/forms/{formId}/responses).
 *
 * 본문에 있는 것은 답뿐이다. 응답자(mbrId)는 @CurrentMember에서, 상태(rspnsSttsCd)와 제출
 * 일시(sbmsnDt)는 서버가 고정값·주입된 Clock에서 채운다 (LY-05) — 본문으로 받으면 남의 이름으로
 * 제출하거나 마감 직전 시각을 조작한 응답을 만들 수 있고, 셋 다 사후에 되돌릴 수 없는 값이다.
 *
 * rspnsCn을 Map이 아니라 ResponseContent로 받는 것은 저장 타입과 요청 타입을 같은 것으로 두기
 * 위해서다. 답을 꺼내는 규칙(다중선택은 배열, 나머지는 문자열)이 한 군데에만 있어야 검증과
 * 저장이 같은 뜻으로 읽는다.
 *
 * 빈 객체({})는 허용한다 — 필수 문항이 하나도 없는 폼은 아무것도 채우지 않고 낼 수 있고,
 * 필수 문항이 있다면 거절 이유는 "본문이 비었다"가 아니라 REQUIRED_ANSWER_MISSING이어야 한다.
 * 반면 rspnsCn 자체가 없는 요청은 낼 수 있는 답이 무엇인지 서버가 추측하게 되므로 막는다.
 */
public record FormResponseSubmitRequest(@NotNull ResponseContent rspnsCn) {}
