package org.sscc.ssccopsserver.domain.academicprogram.code.error;

import org.springframework.http.HttpStatus;
import org.sscc.ssccopsserver.global.apipayload.code.error.ErrorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

/*
 * 학술관리 도메인 전용 에러 코드 (#130).
 *
 * 권한 부족(403 FORBIDDEN)은 여기 정의하지 않는다 — @RequireAuthority AOP가 던지는
 * MemberErrorCode.AUTHORITY_REQUIRED를 그대로 쓴다(#9).
 */
@Getter
@AllArgsConstructor
public enum AcademicProgramErrorCode implements ErrorCode {

    // 404 — 없는 typeCd로 조회·수정·활성 전환을 시도했을 때
    ACADEMIC_PROGRAM_TYPE_NOT_FOUND(
            HttpStatus.NOT_FOUND, "ACADEMIC_PROGRAM_TYPE_NOT_FOUND", "학술 활동 유형을 찾을 수 없습니다."),

    /*
     * 409 — 이미 있는 typeCd로 등록할 때. 선조회만으로는 동시 요청을 막지 못하므로
     * academic_program_type_cd UNIQUE(PK) 위반(DataIntegrityViolationException)도 같은
     * 코드로 옮긴다 (AuthorityAdminServiceImpl.createAuthority와 같은 판단).
     */
    ACADEMIC_PROGRAM_TYPE_CODE_DUPLICATED(
            HttpStatus.CONFLICT, "ACADEMIC_PROGRAM_TYPE_CODE_DUPLICATED", "이미 있는 유형 코드입니다."),

    // 404 — 없는 academicProgramId로 단건 조회를 시도했을 때 (#131)
    ACADEMIC_PROGRAM_NOT_FOUND(
            HttpStatus.NOT_FOUND, "ACADEMIC_PROGRAM_NOT_FOUND", "학술 활동을 찾을 수 없습니다."),

    /*
     * 400 — 목록 조회(#131)의 커서가 형식을 벗어났거나 요청한 정렬과 다를 때. 코드 문자열이
     * VALIDATION_FAILED인 것은 work 도메인의 OperationErrorCode.INVALID_CURSOR와 같은 판단이다
     * — 첫 페이지로 조용히 되돌리면 클라이언트가 목록이 잘렸다는 것을 알아채지 못한다.
     */
    INVALID_CURSOR(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "잘못된 커서입니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
