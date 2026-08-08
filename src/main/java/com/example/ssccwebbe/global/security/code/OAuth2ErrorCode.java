package com.example.ssccwebbe.global.security.code;

import org.springframework.http.HttpStatus;

import com.example.ssccwebbe.global.apipayload.code.error.ErrorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum OAuth2ErrorCode implements ErrorCode {

    // OAuth2 관련 401 UNAUTHORIZED 에러
    UNSUPPORTED_PROVIDER(HttpStatus.UNAUTHORIZED, "OAUTH4001", "지원하지 않는 소셜 로그인입니다."),
    AUTHENTICATION_FAILED(HttpStatus.UNAUTHORIZED, "OAUTH4002", "소셜 로그인에 실패했습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
