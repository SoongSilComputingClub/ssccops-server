package org.sscc.ssccopsserver.domain.example.code;

import org.springframework.http.HttpStatus;
import org.sscc.ssccopsserver.global.apipayload.code.error.ErrorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ExampleErrorCode implements ErrorCode {

    // Example 관련 404 NOT_FOUND 에러
    EXAMPLE_NOT_FOUND(HttpStatus.NOT_FOUND, "EXAMPLE4041", "해당 예시를 찾을 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
