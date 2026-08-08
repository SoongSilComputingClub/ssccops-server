package com.example.ssccwebbe.domain.applyform.code;

import org.springframework.http.HttpStatus;

import com.example.ssccwebbe.global.apipayload.code.error.ErrorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ApplyFormErrorCode implements ErrorCode {
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "APPLYFORM401", "인증 정보가 없습니다."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "APPLYFORM4041", "해당 유저를 찾을 수 없습니다."),
    APPLY_FORM_NOT_FOUND(HttpStatus.NOT_FOUND, "APPLYFORM4042", "작성된 지원서를 찾을 수 없습니다."),
    APPLY_FORM_ALREADY_EXISTS(HttpStatus.CONFLICT, "APPLYFORM4091", "이미 지원서를 작성했습니다."),
    INVALID_INTERVIEW_TIMES(HttpStatus.BAD_REQUEST, "APPLYFORM4001", "면접 희망 시간이 올바르지 않습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
