package org.sscc.ssccopsserver.domain.user.code;

import org.springframework.http.HttpStatus;
import org.sscc.ssccopsserver.global.apipayload.code.error.ErrorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum UserErrorCode implements ErrorCode {

    // User 관련 404 NOT_FOUND 에러
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER4041", "해당 유저를 찾을 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
