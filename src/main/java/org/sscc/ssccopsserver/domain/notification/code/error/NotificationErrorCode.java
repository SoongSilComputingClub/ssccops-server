package org.sscc.ssccopsserver.domain.notification.code.error;

import org.springframework.http.HttpStatus;
import org.sscc.ssccopsserver.global.apipayload.code.error.ErrorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

/*
 * 알림 도메인 전용 에러 코드 (ssccops#446).
 *
 * 코드 문자열은 운영 도메인과 같은 영문 UPPER_SNAKE_CASE이며 정의서 03_오류_코드의 어휘를 쓴다 —
 * 프론트가 코드 문자열로 분기하므로 새 문자열을 만들지 않는다.
 */
@Getter
@AllArgsConstructor
public enum NotificationErrorCode implements ErrorCode {

    /*
     * 404 — 알림이 없다.
     *
     * **남의 알림도 이 하나다.** 알림은 자기 것만 다루는 자원이라 «있는데 네 것이 아니다»(403)를
     * 나누면 식별자가 연속 정수인 표에서 어느 번호가 존재하는지가 드러난다. `@RequireAuthority`가
     * 403으로 답하는 것(AGENTS.md «404로 감추지 않는다»)은 **권한** 판정이고, 이쪽은 소유 판정이다 —
     * 회원 본인 자원(공유 토큰·응답)이 같은 태도를 취한다.
     */
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "NOT_FOUND", "알림을 찾을 수 없습니다."),

    /*
     * 400 — 목록 커서를 해독할 수 없다. 형식이 깨진 커서다(OperationErrorCode.INVALID_CURSOR와
     * 같은 판단 — 첫 페이지로 조용히 되돌리면 클라이언트가 무한히 처음부터 다시 받는다).
     */
    INVALID_CURSOR(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "잘못된 커서입니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
