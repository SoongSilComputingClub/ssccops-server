package org.sscc.ssccopsserver.domain.academicprogram.code.error;

import org.springframework.http.HttpStatus;
import org.sscc.ssccopsserver.global.apipayload.code.error.ErrorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

/*
 * 학술관리 도메인 전용 에러 코드 (#130).
 *
 * @RequireAuthority AOP가 던지는 권한 부족(403)은 MemberErrorCode.AUTHORITY_REQUIRED를 그대로
 * 쓴다(#9) — 여기 정의하지 않는다. 다만 소유권 판정(AcademicProgramOwnershipPolicy, #133 —
 * leadrMbrId 본인 여부)은 AOP가 알 수 없는 레코드 단위 판정이라 다른 층이며, 운영 도메인의
 * OperationErrorCode.FORBIDDEN(SubWorkOwnershipPolicy)과 같은 이유로 이 enum이 FORBIDDEN을
 * 따로 갖는다.
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
    INVALID_CURSOR(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "잘못된 커서입니다."),

    /*
     * 403 — leadrMbrId 본인이 아닌 회원이 소유권 판정이 걸린 동작을 시도할 때
     * (AcademicProgramOwnershipPolicy, #133). 코드 문자열은 OperationErrorCode.FORBIDDEN·
     * MemberErrorCode.AUTHORITY_REQUIRED와 같은 FORBIDDEN이다(화면이 보기엔 같은 거절이다).
     */
    FORBIDDEN(HttpStatus.FORBIDDEN, "FORBIDDEN", "권한이 없습니다."),

    /*
     * 409 — AcademicProgramTransition 전이표에 없는 조합(START_RECRUITMENT를 ONGOING에서
     * 다시 부르는 등)을 요청했을 때 (#133).
     */
    INVALID_ACADEMIC_PROGRAM_TRANSITION(
            HttpStatus.CONFLICT, "INVALID_ACADEMIC_PROGRAM_TRANSITION", "허용되지 않는 상태 전이입니다."),

    /*
     * 409 — START_RECRUITMENT 시도했는데 Event.form_id가 없을 때 (#133). 승인 후속 처리
     * (AcademicProgramApprovalEffectsService)가 생성 시점에 항상 빈 폼을 만들어 연결하므로
     * 정상 흐름에서는 발생하지 않는다 — 데이터 정합성이 깨진 경우에 대한 방어적 코드다.
     */
    FORM_NOT_LINKED(HttpStatus.CONFLICT, "FORM_NOT_LINKED", "연결된 모집 폼이 없습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
