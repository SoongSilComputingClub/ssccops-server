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
     * 있어서다. #21에서 "안전한 연결 절차는 명부 이관 기능을 설계할 때 함께 다룬다"고 미뤄
     * 두었던 그 절차가 **POST /v1/members/link**다 (#86 · ssccops#78 A안). 학번·회원명·연락처
     * 3종이 모두 일치해야 연결되며, 연락처는 MEMBER_MANAGE 없이는 조회되지 않는 값이라
     * 명부를 봤다는 것만으로는 알 수 없다.
     *
     * 그래서 메시지가 "중복이다"에서 끝나지 않고 **연결 경로를 안내**한다 — 이관된 본인이
     * 여기 걸렸을 때 화면이 막다른 길이 아니라 다음 단계를 보여줄 수 있어야 한다.
     */
    STUDENT_NUMBER_DUPLICATED(
            HttpStatus.CONFLICT,
            "STUDENT_NUMBER_DUPLICATED",
            "이미 등록된 학번입니다. 명부에 등록된 본인이라면 계정 연결을 진행하십시오."),

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
    EMPTY_CSV_FILE(HttpStatus.BAD_REQUEST, "EMPTY_CSV_FILE", "데이터가 없는 CSV 파일입니다."),

    /*
     * 404 — 없는 역할 배정을 수정하려 할 때, 또는 **다른 회원의 배정**을 경로에 섞어 보냈을 때 (#81).
     *
     * 두 경우를 같은 코드로 내린다. 나누면 "그 회원에게 그 배정이 있는지"가 코드 문자열로 새어
     * 나가며, 폼 응답의 범위 검사(#37 FORM_RESPONSE_NOT_FOUND)가 세운 선례와 같은 판단이다.
     * 경로에 회원과 배정이 둘 다 있는데 배정 식별자만 보면 /v1/members/1/roles/999가 남의 배정을
     * 종료시킨다.
     */
    MEMBER_ROLE_ASSIGNMENT_NOT_FOUND(
            HttpStatus.NOT_FOUND, "MEMBER_ROLE_ASSIGNMENT_NOT_FOUND", "회원의 역할 배정을 찾을 수 없습니다."),

    /*
     * 400 — 역할 종료일이 시작일보다 이를 때 (#81).
     *
     * 코드 문자열을 VALIDATION_FAILED로 두는 것은 프론트 입장에서 입력값 오류이기 때문이다
     * (SIGNUP_STATUS_NOT_ALLOWED와 같은 방식). 애노테이션으로 막을 수 없는 것은 비교 대상인
     * 시작일이 요청 본문이 아니라 저장된 행에 있어서다.
     */
    ROLE_PERIOD_INVALID(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "역할 종료일은 시작일보다 이를 수 없습니다."),

    /*
     * 409 — 같은 역할을 기간이 겹치게 두 번 부여하려 할 때 (#81).
     *
     * **기간이 겹치지 않는 재임은 막지 않는다.** 작년 국장이 올해 다시 국장이 되는 것은 정상이며,
     * 그 두 행이 따로 남아야 "언제부터 언제까지 국장이었는가"가 보존된다. 막는 것은 같은 사람에게
     * 같은 역할이 동시에 두 번 붙는 상태 하나뿐이다 — 인가에는 영향이 없지만(하나만 유효해도
     * 통과한다) 역할 상세의 재임자 목록에 같은 이름이 두 줄 서고, 종료 조작이 어느 행을 끝내야
     * 하는지 알 수 없게 된다.
     *
     * 종료일이 NULL인 배정은 무기한이므로 이후의 어떤 시작일과도 겹친다.
     */
    ROLE_ALREADY_ASSIGNED(HttpStatus.CONFLICT, "ROLE_ALREADY_ASSIGNED", "이미 같은 기간에 부여된 역할입니다."),

    /*
     * 400 — 재학 회원의 학과·학년을 비우는 수정 (#77).
     *
     * 가입에서 @AssertTrue가 막는 것과 같은 규칙이며 판정도 같은 자리
     * (AcademicProfilePolicy)에서 한다. 수정 요청에는 회원 상태가 실려
     * 있지 않아(상태는 전용 API로만 바뀐다) 요청 DTO 혼자서는 판단할 수 없고, 회원을 읽은 뒤
     * 서비스가 던진다.
     *
     * 코드 문자열을 VALIDATION_FAILED로 두는 것은 SIGNUP_STATUS_NOT_ALLOWED와 같은 이유다 —
     * 프론트에게는 입력값 오류이고, 정의서 03_오류_코드에 없는 코드를 새로 만들지 않는다.
     */
    ACADEMIC_PROFILE_REQUIRED(
            HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "재학 회원은 학과·학년을 입력해야 합니다."),

    /*
     * 400 — 지금과 같은 값으로 등급·상태를 바꾸려 할 때 (#78).
     *
     * 통과시키면 "임시회원 → 임시회원" 같은 행이 mbr_grd_hstry에 쌓여 실제 승급 시점을 찾을 수
     * 없게 된다. 이력은 되돌릴 수 없으므로(행이 updatable = false로 잠겨 있다) 들어오기 전에
     * 막는 것이 유일한 방어선이다.
     *
     * 코드 문자열이 VALIDATION_FAILED가 아니라 전용 값인 것은 화면이 이것만 다르게 안내해야
     * 하기 때문이다 — 필수값 누락과 뭉뚱그리면 "무엇이 잘못됐는지" 대신 "다시 확인하세요"가 된다.
     */
    NO_CHANGE(HttpStatus.BAD_REQUEST, "NO_CHANGE", "현재와 같은 값으로는 변경할 수 없습니다."),

    /*
     * 400 — 등급·상태의 적용 일자가 미래일 때 (#78).
     *
     * mbr의 등급·상태는 이 요청으로 **지금 바뀐다.** 미래 일자를 받아들이면 "3월 1일부터 휴학"이
     * 이력에는 적혀 있는데 회원은 오늘부터 휴학인 상태가 되어 둘이 어긋난다. 예약 변경은 적용
     * 시점에 실제로 값을 바꿔 줄 장치가 있어야 성립하므로 별도 이슈다.
     *
     * 오늘은 시스템 시각이 아니라 주입된 Clock에서 온다 — 클라이언트가 자기 시각으로 오늘을
     * 채워 보내면 시간대가 다른 기기에서 하루 어긋난 이력이 남는다.
     */
    FUTURE_APPLIED_DATE(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "적용 일자는 오늘 이후일 수 없습니다."),

    /*
     * 400 — 종료 예정일을 쓸 수 없는 상태에 실어 보냈을 때 (#78).
     *
     * 조용히 버리지 않고 거절하는 근거는 MemberStatusChangeRequest 주석에 있다 — 이력 행이
     * updatable = false라 나중에 채워 넣을 경로가 없기 때문이다.
     */
    STATUS_END_DATE_NOT_ALLOWED(
            HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "종료 예정일은 휴학·군휴학 상태에만 지정할 수 있습니다."),

    // 400 — 종료 예정일이 적용 일자보다 앞설 때 (#78). 시작하기 전에 끝나는 상태는 성립하지 않는다
    STATUS_END_DATE_BEFORE_APPLIED(
            HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "종료 예정일은 적용 일자보다 앞설 수 없습니다."),

    /*
     * 409 — 이관 실행 요청의 fileToken이 지금 올라온 파일의 해시와 다를 때 (#85 · BR-M48).
     *
     * 사전 검증(#84)이 돌려준 토큰을 실행 요청이 되돌려 주게 해서 **확인한 파일과 넣는 파일이
     * 같은 것임**을 서버가 확인한다. 두 요청 사이에 파일이 바뀌면 운영자가 한 번도 본 적 없는
     * 128건이 그대로 들어가고, 사전 검증 단계 자체가 의미를 잃는다.
     *
     * 400이 아니라 409인 것은 요청의 모양이 틀린 것이 아니라 **서버가 쥔 사실과 어긋나는** 것이기
     * 때문이다 — 운영자가 할 일도 "값을 고친다"가 아니라 "검증을 다시 받는다"이다.
     */
    IMPORT_FILE_MISMATCH(
            HttpStatus.CONFLICT, "IMPORT_FILE_MISMATCH", "검증한 파일과 다른 파일입니다. 검증을 다시 수행하십시오."),

    /*
     * 404 — 이관 회원 계정 연결이 본인 확인에 실패했을 때 (#86 · VR-M23).
     *
     * **어느 항목이 틀렸는지 밝히지 않는다.** "학번은 맞고 이름이 틀립니다"를 내려 주면 이
     * 엔드포인트가 곧 명부 조회 도구가 된다 — 학번을 바꿔 가며 부르면 누가 명부에 있는지,
     * 그 사람의 이름이 무엇인지가 응답으로 새어 나간다. 그래서 세 가지 실패(명부에 없는 학번 ·
     * 이름 불일치 · 연락처 불일치)가 **한 코드 한 문구**다. 연락처가 NULL인 이관 회원도 여기로
     * 떨어진다 — 비교할 값이 없으면 어떤 입력도 일치로 볼 수 없다.
     *
     * 400이 아니라 404인 것은 요청의 모양이 틀린 것이 아니라 **가리키는 회원을 찾지 못한**
     * 것이기 때문이다.
     */
    MEMBER_LINK_FAILED(HttpStatus.NOT_FOUND, "MEMBER_LINK_FAILED", "일치하는 회원 정보를 찾을 수 없습니다."),

    /*
     * 409 — 연결하려는 명부 회원이 이미 다른 계정과 연결돼 있을 때 (#86).
     *
     * MEMBER_LINK_FAILED와 나누는 근거는 **이 코드에 닿은 사람은 이미 본인 확인을 통과했다**는
     * 점이다. 학번·이름·연락처 3종이 모두 맞았다는 뜻이고, 연락처는 명부를 봐서는 알 수 없는
     * 값이라 이 응답이 새로 알려 주는 사실이 없다. 대신 화면은 "정보가 틀렸다"가 아니라
     * "다른 계정이 이미 쓰고 있으니 운영진에게 문의하라"고 안내해야 한다.
     *
     * 선조회로도 대부분 걸리지만, 같은 회원 행에 두 계정이 동시에 연결을 시도하면
     * uk_mbr_auth_user_id 위반으로만 드러나므로 그 경로에서도 같은 코드로 내린다
     * (가입의 ALREADY_SIGNED_UP과 같은 방식).
     */
    MEMBER_ALREADY_LINKED(HttpStatus.CONFLICT, "MEMBER_ALREADY_LINKED", "이미 다른 계정과 연결된 회원입니다."),

    /*
     * 429 — 계정 연결 시도 횟수를 넘겼을 때 (#86 · VR-M24).
     *
     * 제한이 없으면 학번을 바꿔 가며 부르는 것만으로 명부 전체를 훑을 수 있다 — 실패가 한
     * 코드로 뭉뚱그려져 있어도 "성공했는가"는 그 자체로 명부의 존재 여부를 알려 준다.
     * 제한 단위·잠금 시간과 그 근거는 MemberLinkAttemptLimiter의 주석에 있다.
     */
    TOO_MANY_LINK_ATTEMPTS(
            HttpStatus.TOO_MANY_REQUESTS,
            "TOO_MANY_LINK_ATTEMPTS",
            "연결 시도가 너무 많습니다. 잠시 후 다시 시도하십시오.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
