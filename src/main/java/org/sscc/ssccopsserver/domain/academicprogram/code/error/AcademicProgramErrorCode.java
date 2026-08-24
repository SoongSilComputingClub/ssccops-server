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
    FORM_NOT_LINKED(HttpStatus.CONFLICT, "FORM_NOT_LINKED", "연결된 모집 폼이 없습니다."),

    /*
     * 404 — 회차 기록(#135)이 가리키는 curriculumItemId가 그 활동의 것이 아닐 때. 없는
     * 식별자와 남의 활동 커리큘럼을 같은 코드로 묶는다 — 코드를 나누면 번호를 바꿔 가며
     * 부르는 것만으로 다른 활동에 몇 번 회차가 있는지가 새어 나간다(폼 응답의
     * FORM_RESPONSE_NOT_FOUND와 같은 판단).
     */
    CURRICULUM_ITEM_NOT_FOUND(
            HttpStatus.NOT_FOUND, "CURRICULUM_ITEM_NOT_FOUND", "커리큘럼 항목을 찾을 수 없습니다."),

    // 404 — 없는 sessionId이거나 다른 활동에 속한 회차일 때 (#135). 위와 같은 이유로 한 코드다
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "회차 기록을 찾을 수 없습니다."),

    /*
     * 409 — 이미 실적이 있는 커리큘럼 항목에 신규 제출(POST)을 시도했을 때 (#135). 계획 1개당
     * 실적은 최대 1개다(curriculum_item_id UNIQUE). 선조회만으로는 동시 요청을 막지 못하므로
     * UNIQUE 위반(DataIntegrityViolationException)도 같은 코드로 옮긴다.
     */
    SESSION_ALREADY_EXISTS(HttpStatus.CONFLICT, "SESSION_ALREADY_EXISTS", "이미 기록된 회차입니다."),

    /*
     * 409 — 지금 쓸 수 있는 상태가 아닌 회차에 재제출(PUT)을 시도했을 때 (#135). 재제출은
     * REVISION_REQUESTED 전용이며 SUBMITTED(국장 검토 대기)·APPROVED(확정 이력)는 손대지
     * 않는다 — 판정 자체는 SessionStatus.allowsRecording이 갖는다.
     */
    SESSION_NOT_EDITABLE(HttpStatus.CONFLICT, "SESSION_NOT_EDITABLE", "지금은 회차 기록을 수정할 수 없습니다."),

    /*
     * 400 — attendances에 그 활동의 확정 팀원(event_ptcp, CONFIRMED)이 아닌 대상이 실려 왔을 때
     * (#135, 설계 결정 #3). 대기자·취소자·다른 활동의 참가자가 모두 여기로 온다 — 조용히
     * 버리면 출석부의 totalCount가 화면이 보낸 명단과 어긋난 채로 저장된다.
     *
     * 같은 참가자가 두 번 실려 온 것도 같은 코드다. 폼 라벨 교체가 중복을 한 번으로 접는 것과
     * 갈리는데, 라벨은 붙었는지 여부뿐이지만 출석은 값이 딸린 체크라 두 줄이 서로 다른 답을
     * 실을 수 있고 그중 무엇이 맞는지 정할 규칙이 없다.
     *
     * 출석 정정(#137 · PATCH)도 같은 코드를 쓰되 판정 기준이 한 겹 좁다 — 그쪽은 **그 회차의
     * 출석부에 이미 줄이 있는** 참가자만 받는다. 정정은 명단을 바꾸는 일이 아니라 체크 값만
     * 바꾸는 일이라, 줄이 없는 대상은 확정 팀원이더라도 여기로 온다
     * (AttendanceServiceImpl.correctAttendances).
     */
    INVALID_ATTENDANCE_TARGET(
            HttpStatus.BAD_REQUEST,
            "INVALID_ATTENDANCE_TARGET",
            "출석 대상이 아닌 참가자가 포함돼 있거나 같은 참가자가 중복됐습니다."),

    /*
     * 409 — SessionTransition 전이표에 없는 조합을 요청했을 때 (#136). 실제로 여기 걸리는 것은
     * SUBMITTED가 아닌 회차에 대한 승인·수정요청이며, 그중 APPROVED는 되돌리지 않는다는 원칙
     * (학술관리_데이터모델.md §3)이 만든 자리다.
     *
     * 재제출(#135)이 만나는 SESSION_NOT_EDITABLE과 코드를 나눈 것은 주체와 다음 행동이 다르기
     * 때문이다 — 그쪽은 스터디장에게 "지금은 못 고친다"이고 이쪽은 국장에게 "이미 처리된
     * 회차다"이다. 화면도 다르고, 한 코드로 묶으면 어느 화면의 안내를 골라야 할지 알 수 없다.
     */
    INVALID_SESSION_TRANSITION(
            HttpStatus.CONFLICT, "INVALID_SESSION_TRANSITION", "허용되지 않는 회차 상태 전이입니다."),

    /*
     * 400 — 사유 없이 수정요청을 하려 할 때 (#136). 수정요청은 스터디장에게 "무엇을 고쳐야
     * 하는가"를 알리는 통보이고, 그 사유가 남는 자리는 academic_program_aprv의 최신 행 하나뿐이라
     * (재제출은 이력을 남기지 않는다, 데이터모델 §7) 비워 두면 통보 자체가 성립하지 않는다.
     * 공백만 있는 문자열도 여기 걸린다 — DB의 NOT NULL이 막지 못하는 자리다.
     *
     * 승인은 사유가 선택이라 DTO의 @NotBlank로는 막을 수 없다. 필수 여부가 함께 온 transition에
     * 달려 있고, 조건부 검증을 Bean Validation으로 표현해도 전역 핸들러가 VALIDATION_FAILED로
     * 뭉개 웹이 "사유를 적으라"는 안내를 고를 수 없다(폼 응답 검토 #141의 REVIEW_OPINION_REQUIRED와
     * 같은 판단). 실제로 막는 자리는 SessionEntity.changeStatus다.
     */
    REVISION_REASON_REQUIRED(
            HttpStatus.BAD_REQUEST, "REVISION_REASON_REQUIRED", "수정요청은 사유를 반드시 입력해야 합니다."),

    /*
     * 400 — 인증사진 업로드(#137)의 fileExt가 허용 목록 밖일 때. 허용 목록 자체는 행사 이미지와
     * 같은 EventImageType이며(형식을 늘리는 자리를 한 곳으로 묶는다) SVG를 빼는 이유도 같다 —
     * 공개 도메인에서 그대로 열리므로 스크립트를 담을 수 있는 문서를 허용하면 XSS 경로가 된다.
     *
     * 코드 문자열이 EventErrorCode.UNSUPPORTED_IMAGE_TYPE과 같은 것은 화면이 고를 안내가 같기
     * 때문이고, 그런데도 상수를 도메인마다 따로 두는 것은 이 레포의 규칙이다(AGENTS.md —
     * 같은 의미라도 도메인이 다르면 별개 상수다).
     */
    UNSUPPORTED_IMAGE_TYPE(HttpStatus.BAD_REQUEST, "UNSUPPORTED_IMAGE_TYPE", "지원하지 않는 이미지 형식입니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
