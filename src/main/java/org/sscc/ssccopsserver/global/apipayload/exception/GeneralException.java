package org.sscc.ssccopsserver.global.apipayload.exception;

import org.sscc.ssccopsserver.global.apipayload.code.error.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class GeneralException extends RuntimeException {
    private final ErrorCode errorCode;
}
