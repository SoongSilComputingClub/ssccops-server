package com.example.ssccwebbe.global.apipayload.exception;

import com.example.ssccwebbe.global.apipayload.code.error.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class GeneralException extends RuntimeException {
    private final ErrorCode errorCode;
}
