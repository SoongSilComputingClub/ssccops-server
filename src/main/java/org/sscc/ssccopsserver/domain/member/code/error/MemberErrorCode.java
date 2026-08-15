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
     *
     * 코드 문자열이 VALIDATION_FAILED가 아니라 전용 값인 것은 #65에서 이 규칙이 실제로 화면에
     * 노출되는 조작(상위 변경)이 됐기 때문이다 — 필수값 누락과 같은 안내로 뭉뚱그리면 화면이
     * "왜 거절됐는가"를 설명하지 못한다. #9 시점에는 API가 없어 도달할 수 없는 코드였다.
     */
    AUTHORITY_CYCLE_DETECTED(
            HttpStatus.BAD_REQUEST, "AUTHORITY_CYCLE_DETECTED", "권한의 상위를 순환되게 지정할 수 없습니다."),

    /*
     * 400 — 가입 시 선택할 수 없는 회원 상태(휴학·졸업 외의 학적, 탈퇴·제명 등).
     *
     * 기준 코드에는 있으나 가입 경로에서만 막히는 값이라 INVALID_CODE_VALUE와 구분한다.
     * 코드 문자열을 VALIDATION_FAILED로 두는 것은 프론트 입장에서 입력값 오류이기 때문이다
     * (OperationErrorCode.OWNER_NOT_ACTIVE_MEMBER와 같은 방식).
     */
    SIGNUP_STATUS_NOT_ALLOWED(
            HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "가입 시 선택할 수 없는 회원 상태입니다."),

    /*
     * 400 — 회원 목록 조회(#76)의 커서를 해독할 수 없을 때. 형식이 깨졌거나 다른 정렬 기준으로
     * 받은 커서다. 코드 문자열을 VALIDATION_FAILED로 두는 것은 운영 도메인의 INVALID_CURSOR와
     * 같은 이유다 — 정의서 03_오류_코드에 커서 전용 코드가 없고, 프론트가 코드 문자열로
     * 분기하므로 정의서에 없는 코드를 새로 만들지 않는다.
     */
    INVALID_CURSOR(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "잘못된 커서입니다."),

    // 404 — 인증 이후 회원이 삭제된 경우처럼, 주체는 있으나 회원 레코드를 못 찾을 때
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "NOT_FOUND", "회원을 찾을 수 없습니다."),

    // 404 — 없는 권한 코드를 조회·수정·삭제·부여하려 할 때 (#65)
    AUTHORITY_NOT_FOUND(HttpStatus.NOT_FOUND, "AUTHORITY_NOT_FOUND", "권한을 찾을 수 없습니다."),

    // 404 — 없는 역할의 권한을 조회·교체하려 할 때 (#65)
    ROLE_NOT_FOUND(HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND", "역할을 찾을 수 없습니다."),

    /*
     * 404 — 없는 역할 분류를 수정·삭제하려 할 때(#80), 없는 분류로 역할을 만들거나 옮기려 할 때(#79).
     *
     * 코드를 감추지 않고 404로 내리는 것은 분류가 조직 구조를 드러내지 않기 때문이다 —
     * 목록 조회 자체가 인증만으로 열려 있으므로 존재 여부를 숨길 이유가 없다.
     */
    ROLE_CLASSIFICATION_NOT_FOUND(
            HttpStatus.NOT_FOUND, "ROLE_CLASSIFICATION_NOT_FOUND", "역할 분류를 찾을 수 없습니다."),

    /*
     * 409 — sys_yn = true인 권한을 지우거나 코드를 바꾸려 할 때 (#65 · BR-M33).
     *
     * 코드(@RequireAuthority)가 이 값을 직접 가리키므로 지워지는 순간 그 코드를 요구하는
     * 엔드포인트가 아무도 통과하지 못하는 상태가 된다 — 화면 조작 한 번으로 인가가 무력화되는
     * 것을 막는 자리다. 이름·설명·트리 위치는 여전히 바꿀 수 있다.
     */
    SYSTEM_AUTHORITY_IMMUTABLE(
            HttpStatus.CONFLICT, "SYSTEM_AUTHORITY_IMMUTABLE", "시스템 권한은 삭제하거나 코드를 바꿀 수 없습니다."),

    /*
     * 409 — 이미 쓰이고 있는 권한을 지우려 할 때 (#65).
     *
     * '쓰이고 있다'는 두 가지다: 어느 역할엔가 부여돼 있거나(role_authrt_rel), 자식 권한이
     * 달려 있거나. 앞은 회수를 먼저 하도록 안내하기 위한 것이고, 뒤는 부모를 지우면 자식이
     * 갈 곳을 잃기 때문이다 — 자식을 조부모로 조용히 옮기면 그 순간 인가 범위가 바뀐다.
     */
    AUTHORITY_IN_USE(HttpStatus.CONFLICT, "AUTHORITY_IN_USE", "부여되었거나 하위 권한이 있는 권한은 삭제할 수 없습니다."),

    // 409 — 이미 있는 권한 코드로 사용자 정의 권한을 만들려 할 때 (#65)
    AUTHORITY_CODE_DUPLICATED(
            HttpStatus.CONFLICT, "AUTHORITY_CODE_DUPLICATED", "이미 존재하는 권한 코드입니다."),

    /*
     * 409 — 사용자 정의 권한의 코드를 바꾸려 할 때 (#65).
     *
     * 코드는 PK이고 role_authrt_rel·자식 권한이 FK로 가리키므로 값 하나를 갈아 끼우는 조작이
     * 아니다. 시스템 권한과 코드를 나누는 것은 막다른 길인지 아닌지가 다르기 때문이다 —
     * 사용자 정의 권한은 새로 만들고 기존 것을 지우면 되지만, 시스템 권한은 그 경로 자체가 없다.
     */
    AUTHORITY_CODE_IMMUTABLE(
            HttpStatus.CONFLICT,
            "AUTHORITY_CODE_IMMUTABLE",
            "권한 코드는 바꿀 수 없습니다. 새로 만든 뒤 기존 권한을 삭제하십시오."),

    /*
     * 409 — SYSTEM 역할 분류를 지우거나 이름을 바꾸려 할 때 (#80).
     *
     * data.sql이 '최고관리자' 역할을 이 분류에 두고, 그 역할만이 SUPER 권한을 갖는다 —
     * 분류가 사라지면 최초 가입자 부트스트랩(#71)의 시드가 통째로 깨져 새 환경에서 아무도
     * ROLE_MANAGE를 얻지 못하는 닫힌 고리로 되돌아간다. 시스템 권한을 sys_yn으로 지키는 것과
     * 같은 자리다.
     *
     * **이름까지 막는 것이 시스템 권한(SYSTEM_AUTHORITY_IMMUTABLE)과 갈리는 지점이다.** 저쪽은
     * 조직이 부르는 이름과 코드가 참조하는 값이 따로 있어 이름 변경이 무해했지만, 이쪽의
     * '시스템'은 조직이 만든 자리가 아니라 시스템이 쓰는 역할을 담는 칸이라는 표시 그 자체다 —
     * 이름을 '홍보국'으로 바꾸면 최고관리자가 조직 직책인 것처럼 화면에 서게 된다.
     *
     * 표준코드에 등재된 5종(POSITION·DEPT·PROJECT·STUDY·EVENT)은 여기 걸리지 않는다.
     * 코드값이 유지되면 참조가 깨지지 않고, 표시명은 조직이 정할 일이다.
     */
    SYSTEM_ROLE_CLASSIFICATION_IMMUTABLE(
            HttpStatus.CONFLICT,
            "SYSTEM_ROLE_CLASSIFICATION_IMMUTABLE",
            "시스템 역할 분류는 삭제하거나 이름을 바꿀 수 없습니다."),

    /*
     * 409 — 소속 역할이 있는 역할 분류를 지우려 할 때 (#80).
     *
     * role.role_clsf_cd가 NOT NULL FK라 지우면 역할이 갈 곳을 잃는다. 소속 역할을 함께 지우거나
     * 다른 분류로 조용히 옮기지 않는 것은 AUTHORITY_IN_USE와 같은 이유다 — 삭제 한 번으로
     * 조직도가 바뀌는 것을 화면에서 보이게 하려면 옮기는 일을 먼저 하게 해야 한다.
     */
    ROLE_CLASSIFICATION_IN_USE(
            HttpStatus.CONFLICT, "ROLE_CLASSIFICATION_IN_USE", "소속 역할이 있는 역할 분류는 삭제할 수 없습니다."),

    /*
     * 409 — 이미 있는 코드로 역할 분류를 만들려 할 때 (#80).
     *
     * 코드는 운영진이 직접 정하므로(RoleClassificationCreateRequest 주석) 중복은 흔한 실수다.
     * 덮어쓰지 않고 거절해야 남이 만든 분류가 이름만 바뀌어 사라지지 않는다.
     */
    ROLE_CLASSIFICATION_CODE_DUPLICATED(
            HttpStatus.CONFLICT, "ROLE_CLASSIFICATION_CODE_DUPLICATED", "이미 존재하는 역할 분류 코드입니다."),

    /*
     * 409 — 요청자 자신이 ROLE_MANAGE를 잃게 되는 권한 교체 (#65 · VR-M13).
     *
     * 권한 관리 자체가 ROLE_MANAGE를 요구하므로, 마지막 보유자가 스스로 회수하면 화면에서는
     * 아무도 되돌릴 수 없고 DB를 직접 고쳐야 복구된다. 다른 사람이 회수하는 것은 막지 않는다 —
     * 그 경우엔 회수한 쪽이 여전히 관리할 수 있다.
     */
    CANNOT_REVOKE_OWN_ROLE_MANAGE(
            HttpStatus.CONFLICT, "CANNOT_REVOKE_OWN_ROLE_MANAGE", "자신의 권한 관리 권한은 회수할 수 없습니다."),

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
    STUDENT_NUMBER_DUPLICATED(HttpStatus.CONFLICT, "STUDENT_NUMBER_DUPLICATED", "이미 등록된 학번입니다."),

    /*
     * 409 — 이미 있는 이름으로 역할을 만들거나 이름을 바꾸려 할 때 (#79).
     *
     * role_nm에는 UNIQUE 제약이 없다(데이터사전 Not Null = N, UNIQUE 아님). 그런데도 거절하는
     * 이유와 제약을 새로 걸지 않는 이유는 RoleServiceImpl의 주석에 적어 두었다.
     */
    ROLE_NAME_DUPLICATED(HttpStatus.CONFLICT, "ROLE_NAME_DUPLICATED", "이미 존재하는 역할명입니다."),

    /*
     * 409 — 이미 쓰이고 있는 역할을 지우려 할 때 (#79).
     *
     * '쓰이고 있다'는 두 가지다: 누군가에게 배정된 적이 있거나(mbr_role_rel, **종료된 배정도
     * 포함한다**), 권한이 붙어 있거나(role_authrt_rel). 앞은 지우면 "그 사람이 언제 국장이었는지"가
     * 함께 사라지기 때문이고, 뒤는 회수를 먼저 하도록 안내하기 위해서다(AUTHORITY_IN_USE와 같은 태도).
     *
     * 데이터사전의 role에는 use_yn이 없으므로 '비활성화'라는 도피처를 만들지 않는다 — 필요해지면
     * 데이터사전 등재가 먼저다.
     */
    ROLE_IN_USE(HttpStatus.CONFLICT, "ROLE_IN_USE", "배정 이력이나 권한이 있는 역할은 삭제할 수 없습니다."),

    /*
     * 400 — 이관용 CSV로 읽을 수 없는 파일 (#84).
     *
     * 확장자·크기(5MB)·인코딩(UTF-8, BOM 허용)이 한 코드에 묶여 있다. 셋을 나누지 않는 것은
     * 운영자가 할 일이 셋 다 "다른 파일을 내보내 다시 올린다" 하나이기 때문이다 — 무엇이 어긋났는지는
     * 코드가 아니라 message가 전한다. **파싱 전에** 끊으므로 여기 걸린 파일은 한 줄도 읽지 않는다.
     */
    INVALID_CSV_FILE(HttpStatus.BAD_REQUEST, "INVALID_CSV_FILE", "CSV 파일을 읽을 수 없습니다."),

    /*
     * 400 — 컬럼 매핑이 성립하지 않을 때 (#84).
     *
     * 필수 필드(mbrNm·mbrGrdCd·mbrSttsCd)가 매핑되지 않았거나, 파일에 없는 헤더를 가리키거나,
     * 한 필드에 두 컬럼이 매핑된 경우다. 행별 오류로 내리지 않고 요청 전체를 거절하는 것은
     * 매핑이 어긋나면 모든 행이 같은 이유로 틀려 128건짜리 오류 목록이 되기 때문이다.
     */
    CSV_MAPPING_INVALID(HttpStatus.BAD_REQUEST, "CSV_MAPPING_INVALID", "컬럼 매핑이 올바르지 않습니다."),

    /*
     * 400 — 헤더가 없거나 데이터 행이 0건인 CSV (#84).
     *
     * INVALID_CSV_FILE과 나누는 것은 파일 자체는 멀쩡하다는 사실이 다르기 때문이다 — 운영자는
     * 파일을 바꾸는 것이 아니라 내보내기 범위를 다시 잡아야 한다. 빈 결과를 200으로 돌려주지
     * 않는 것은 위저드가 "0건 검증 완료"를 성공으로 읽고 다음 단계로 넘어가기 때문이다.
     */
    EMPTY_CSV_FILE(HttpStatus.BAD_REQUEST, "EMPTY_CSV_FILE", "데이터가 없는 CSV 파일입니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
