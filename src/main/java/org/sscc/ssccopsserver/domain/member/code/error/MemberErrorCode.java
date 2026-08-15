package org.sscc.ssccopsserver.domain.member.code.error;

import org.springframework.http.HttpStatus;
import org.sscc.ssccopsserver.global.apipayload.code.error.ErrorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

/*
 * 회원 도메인 전용 에러 코드.
 *
 * 코드 문자열이 영문 UPPER_SNAKE_CASE인 것은 의도된 것이다 — 개발지침서 EX-10(숫자 코드 금지)과
 * 운영관리 API 정의서 03_오류_코드가 이 표기를 요구하며, 프론트가 코드 문자열로 분기한다.
 * (OperationErrorCode와 같은 이유. 전역 코드 체계 전환은 범위가 커 별도 이슈로 다룬다.)
 */
@Getter
@AllArgsConstructor
public enum MemberErrorCode implements ErrorCode {

    /*
     * 403 — Supabase 인증은 통과했지만 아직 회원가입을 하지 않은 사용자.
     *
     * 401과 구분해야 한다. 토큰이 없거나 무효하면 401이고, 토큰은 유효하나 연결된 mbr이 없으면
     * 이 코드다. 프론트는 이 코드를 받으면 재로그인이 아니라 가입 화면으로 보내야 한다.
     */
    SIGNUP_REQUIRED(HttpStatus.FORBIDDEN, "SIGNUP_REQUIRED", "회원 가입이 필요합니다."),

    /*
     * 403 — 가입은 했으나 요구 권한을 갖지 못한 회원 (#9 · VR-M10).
     *
     * SIGNUP_REQUIRED와 상태 코드는 같지만 뜻이 다르다. 저쪽은 "가입 화면으로 보내라"이고
     * 이쪽은 "이 계정으로는 할 수 없는 일"이다 — 프론트가 코드 문자열로 갈라 다르게 안내한다.
     *
     * **404로 감추지 않는다.** 내부 운영 도구라 자원의 존재를 숨길 이유가 없고, 403이어야
     * "권한이 없다"가 전달된다. 코드 문자열은 운영 도메인의 FORBIDDEN(승인자 판정)과 같은 값을
     * 쓴다 — 화면이 보기에 둘 다 "권한이 없어 막혔다" 하나이고, 나누면 프론트가 같은 안내를
     * 두 번 적어야 한다.
     */
    AUTHORITY_REQUIRED(HttpStatus.FORBIDDEN, "FORBIDDEN", "권한이 없습니다."),

    /*
     * 400 — 권한의 상위를 순환되게 지정했을 때 (#9 · BR-M23).
     *
     * 자기 자신이나 자기 자손을 상위로 두면 트리가 고리가 되고, 하위 권한 하나가 상위 전부를
     * 부여하게 되어 '위→아래 한 방향'이라는 펼침 규칙이 무너진다.
     */
    AUTHORITY_PARENT_CYCLE(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "권한의 상위를 순환되게 지정할 수 없습니다."),

    /*
     * 400 — 가입 시 선택할 수 없는 회원 상태(휴학·졸업 외의 학적, 탈퇴·제명 등).
     *
     * 기준 코드에는 있으나 가입 경로에서만 막히는 값이라 INVALID_CODE_VALUE와 구분한다.
     * 코드 문자열을 VALIDATION_FAILED로 두는 것은 프론트 입장에서 입력값 오류이기 때문이다
     * (OperationErrorCode.OWNER_NOT_ACTIVE_MEMBER와 같은 방식).
     */
    SIGNUP_STATUS_NOT_ALLOWED(
            HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "가입 시 선택할 수 없는 회원 상태입니다."),

    // 404 — 인증 이후 회원이 삭제된 경우처럼, 주체는 있으나 회원 레코드를 못 찾을 때
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "NOT_FOUND", "회원을 찾을 수 없습니다."),

    /*
     * 409 — 이미 가입을 마친 인증 계정이 가입을 다시 요청했을 때.
     *
     * 프론트는 이 코드를 오류 화면이 아니라 "이미 가입됨"으로 읽고 세션을 다시 조회해야 한다.
     * 선조회로도 대부분 걸리지만, 같은 계정의 동시 요청은 uk_mbr_auth_user_id 위반으로만
     * 드러나므로 그 경로에서도 같은 코드로 내린다.
     */
    ALREADY_SIGNED_UP(HttpStatus.CONFLICT, "ALREADY_SIGNED_UP", "이미 가입된 계정입니다."),

    /*
     * 409 — 다른 회원이 이미 쓰고 있는 학번. CSV로 이관된 회원의 학번도 여기에 걸린다.
     *
     * 학번이 일치한다고 그 행에 자동으로 연결하지 않는다 — 학번만 알면 남의 계정을 가로챌 수
     * 있어서다. 안전한 연결 절차는 명부 이관 기능을 설계할 때 함께 다룬다 (#21).
     */
    STUDENT_NUMBER_DUPLICATED(HttpStatus.CONFLICT, "STUDENT_NUMBER_DUPLICATED", "이미 등록된 학번입니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
