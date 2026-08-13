package org.sscc.ssccopsserver.domain.operation.code.error;

import org.springframework.http.HttpStatus;
import org.sscc.ssccopsserver.global.apipayload.code.error.ErrorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

/*
 * 운영 도메인 전용 에러 코드.
 *
 * 코드 문자열이 다른 도메인(EXAMPLE4041 형태)과 다르게 영문 UPPER_SNAKE_CASE인 것은
 * 의도된 것이다. 운영관리 API 정의서 03_오류_코드와 개발지침서 EX-10(숫자 코드 금지)이
 * 이 표기를 요구하며, 프론트가 코드 문자열로 분기하므로 정의서와 어긋나면 안 된다.
 * 전역 코드 체계를 같은 표기로 전환하는 것은 범위가 커 별도 이슈로 다룬다.
 */
@Getter
@AllArgsConstructor
public enum OperationErrorCode implements ErrorCode {

    // 기준 코드 위반(INVALID_CODE_VALUE)은 enum 역직렬화 실패로 전역 핸들러가 먼저 잡으므로
    // CommonErrorCode에 두었다. 여기에 중복 정의하지 않는다.

    // 400 — 담당자로 지정한 회원이 없거나 활동 회원이 아닐 때
    OWNER_NOT_ACTIVE_MEMBER(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "담당자로 지정할 수 없는 회원입니다."),

    // 400 — 종료 일시가 시작 일시보다 빠를 때. DTO 검증을 통과해도 서비스에서 한 번 더 막는다
    INVALID_OPERATION_PERIOD(
            HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "종료 일시는 시작 일시보다 빠를 수 없습니다."),

    // 403 — 국장 미만 권한. 역할 인가가 AOP로 붙기 전까지는 사용처가 없다
    FORBIDDEN(HttpStatus.FORBIDDEN, "FORBIDDEN", "권한이 없습니다."),

    // 404
    OPERATION_NOT_FOUND(HttpStatus.NOT_FOUND, "NOT_FOUND", "운영 건을 찾을 수 없습니다."),
    WORK_NOT_FOUND(HttpStatus.NOT_FOUND, "NOT_FOUND", "업무를 찾을 수 없습니다."),

    // 500 — 감사 로그 기록 실패. 원 트랜잭션도 함께 롤백된다 (EX-13, BR-O12)
    AUDIT_LOG_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "AUDIT_LOG_FAILED", "감사 로그 기록에 실패했습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
