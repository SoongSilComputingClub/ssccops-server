package com.example.ssccopsserver.global.apipayload.exception;

import com.example.ssccopsserver.global.apipayload.code.error.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class GeneralException extends RuntimeException {
    private final ErrorCode errorCode;
}
