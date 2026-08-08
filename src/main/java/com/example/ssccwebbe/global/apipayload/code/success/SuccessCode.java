package com.example.ssccwebbe.global.apipayload.code.success;

import org.springframework.http.HttpStatus;

public interface SuccessCode {
    HttpStatus getHttpStatus();

    String getCode();

    String getMessage();
}
