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

    // 404 — 선택한 하위 업무 유형이 없을 때. 유형은 기준 데이터라 삭제·변경될 수 있다
    SUB_WORK_TYPE_NOT_FOUND(HttpStatus.NOT_FOUND, "NOT_FOUND", "하위 업무 유형을 찾을 수 없습니다."),

    // 404 — 소프트 삭제된 하위 업무도 여기에 걸린다. 존재를 알려주지 않기 위해 409로 나누지 않는다
    SUB_WORK_NOT_FOUND(HttpStatus.NOT_FOUND, "NOT_FOUND", "하위 업무를 찾을 수 없습니다."),

    // 409 — 전이표(TR-01~TR-04)에 없는 상태 전환. 완료 → 진행 되돌리기도 여기에 걸린다
    TRANSITION_NOT_ALLOWED(HttpStatus.CONFLICT, "TRANSITION_NOT_ALLOWED", "현재 상태에서 할 수 없는 작업입니다."),

    // 409 — 완료 체크리스트를 다 채우지 않은 채 완료 승인을 시도했을 때 (TR-03 선행 조건)
    COMPLETION_CRITERIA_UNMET(
            HttpStatus.CONFLICT, "COMPLETION_CRITERIA_UNMET", "완료 조건을 모두 확인해주세요."),

    /*
     * 422 — 반려 사유 누락. Bean Validation으로 잡으면 전역 핸들러가 VALIDATION_FAILED(400)로
     * 바꿔 버리므로, 사유의 필수 여부는 전이 메서드가 판단해 이 코드로 던진다 (VR-O06).
     */
    REASON_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY, "REASON_REQUIRED", "사유를 입력해주세요."),

    // 500 — 감사 로그 기록 실패. 원 트랜잭션도 함께 롤백된다 (EX-13, BR-O12)
    AUDIT_LOG_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "AUDIT_LOG_FAILED", "감사 로그 기록에 실패했습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
