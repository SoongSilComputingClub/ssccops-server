package org.sscc.ssccopsserver.global.apipayload.code.error;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum CommonErrorCode implements ErrorCode {

    // COMMON 4XX
    INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "COMMON400", "파라미터가 올바르지 않습니다."),
    INVALID_BODY(HttpStatus.BAD_REQUEST, "COMMON400", "요청 본문이 올바르지 않습니다."),
    // 아래 두 코드의 문자열만 영문 표기인 것은 운영관리 API 정의서 03_오류_코드와
    // 개발지침서 EX-10(숫자 코드 금지)을 따른 것이다. 전역 체계 전환은 별도 이슈로 다룬다.
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "필수값이 누락되었거나 형식이 올바르지 않습니다."),
    // 코드성 필드(enum)에 기준 코드 밖의 값이 온 경우
    INVALID_CODE_VALUE(HttpStatus.BAD_REQUEST, "INVALID_CODE_VALUE", "기준 코드에 없는 값입니다."),
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "COMMON400", "잘못된 요청입니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "COMMON401", "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "COMMON403", "금지된 요청입니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON404", "찾을 수 없는 리소스입니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "COMMON405", "허용되지 않는 HTTP Method입니다."),

    // COMMON 5XX
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON500", "서버 내부 오류입니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
