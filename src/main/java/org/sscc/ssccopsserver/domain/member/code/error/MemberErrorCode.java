package org.sscc.ssccopsserver.domain.member.code.error;

import org.springframework.http.HttpStatus;
import org.sscc.ssccopsserver.global.apipayload.code.error.ErrorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

/*
 * 회원 도메인 전용 에러 코드.
 *
 * 코드 문자열이 영문 UPPER_SNAKE_CASE인 것은 의도된 것이다 — 개발지침서 EX-10(숫자 코드 금지)과
 * 운영관리 API 정의서 03_오류_코드가 이 표기를 요구하며, 프론트가 코드 문자열로 분기한다.
 * (OperationErrorCode와 같은 이유. 전역 코드 체계 전환은 범위가 커 별도 이슈로 다룬다.)
 */
@Getter
@AllArgsConstructor
public enum MemberErrorCode implements ErrorCode {

    /*
     * 403 — Supabase 인증은 통과했지만 아직 회원가입을 하지 않은 사용자.
     *
     * 401과 구분해야 한다. 토큰이 없거나 무효하면 401이고, 토큰은 유효하나 연결된 mbr이 없으면
     * 이 코드다. 프론트는 이 코드를 받으면 재로그인이 아니라 가입 화면으로 보내야 한다.
     */
    SIGNUP_REQUIRED(HttpStatus.FORBIDDEN, "SIGNUP_REQUIRED", "회원 가입이 필요합니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
