package org.sscc.ssccopsserver.domain.event.code.error;

import org.springframework.http.HttpStatus;
import org.sscc.ssccopsserver.global.apipayload.code.error.ErrorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

/*
 * 행사 도메인 전용 에러 코드 (ssccops#139·#140).
 *
 * 코드 문자열은 웹과 합의된 계약이다 — 프론트가 이 문자열로 분기하므로 임의로 바꾸지 않는다.
 * 영문 UPPER_SNAKE_CASE는 FormErrorCode·MemberErrorCode와 같은 이유다(개발지침서 EX-10).
 */
@Getter
@AllArgsConstructor
public enum EventErrorCode implements ErrorCode {

    // 404 — 행사 자체를 찾을 수 없을 때. DRAFT·ARCHIVED도 관리 API에서는 존재하므로 여기 걸리지 않는다
    EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND", "행사를 찾을 수 없습니다."),

    // 404 — 존재하지 않는 행사 분류. 행사 저장의 eventClsfCd와 분류 관리 경로가 같이 쓴다
    EVENT_CLASSIFICATION_NOT_FOUND(
            HttpStatus.NOT_FOUND, "EVENT_CLASSIFICATION_NOT_FOUND", "행사 분류를 찾을 수 없습니다."),

    /*
     * 400 — 전이표(EventStatusAction)에 없는 상태 전이. 이미 게시된 행사를 또 게시하거나,
     * 작성 중인 행사를 보관하려는 요청이 여기에 걸린다.
     *
     * 409가 아니라 400인 것은 폼(INVALID_FORM_STATUS_TRANSITION)과 같은 판단이다 — 웹은 현재
     * 상태를 이미 화면에 들고 있어 보낼 수 있는 액션이 정해지므로 "보내면 안 되는 요청"에 가깝다.
     */
    INVALID_EVENT_STATUS_TRANSITION(
            HttpStatus.BAD_REQUEST, "INVALID_EVENT_STATUS_TRANSITION", "허용되지 않는 행사 상태 전이입니다."),

    /*
     * 409 — 신청이 발생한 뒤 폼 연결을 바꾸거나 해제하려 할 때 (D11).
     *
     * 폼→행사 역참조 하나로 "이 응답이 어느 행사의 신청인가"가 확정되는 구조라, 제출 이후
     * 응답(SUBMITTED·ACCEPTED·REJECTED)이나 참가자가 생긴 뒤 연결을 움직이면 이미 낸 신청의
     * 소속이 조용히 바뀐다. 임시저장(DRAFT) 응답만 있는 폼은 아직 신청이 없으므로 바꿀 수 있다.
     */
    EVENT_FORM_IN_USE(
            HttpStatus.CONFLICT, "EVENT_FORM_IN_USE", "신청이 발생한 행사의 폼 연결은 변경하거나 해제할 수 없습니다."),

    /*
     * 409 — 다른 행사에 이미 전속된 폼을 연결하려 할 때 (D11 · uk_event_form).
     *
     * 선조회로 대부분 걸리지만 두 행사가 같은 폼을 동시에 연결하면 둘 다 선조회를 통과하므로
     * UNIQUE 위반도 같은 코드로 옮긴다 (#21 학번 중복과 같은 방식).
     */
    FORM_ALREADY_LINKED(HttpStatus.CONFLICT, "FORM_ALREADY_LINKED", "이미 다른 행사에 연결된 폼입니다."),

    /*
     * 409 — 참가자가 있는 행사를 삭제하려 할 때 (D9).
     *
     * 참가자 명단은 활동 이력으로 영구 보존한다(D16). 행사를 지우면 명단이 갈 곳을 잃으므로,
     * 잘못 만든 행사는 참가자가 생기기 전에만 지울 수 있고 그 뒤에는 보관(ARCHIVE)이 경로다.
     */
    EVENT_HAS_PARTICIPANT(HttpStatus.CONFLICT, "EVENT_HAS_PARTICIPANT", "참가자가 있는 행사는 삭제할 수 없습니다."),

    // 409 — 그 분류를 쓰는 행사가 있을 때. 행사를 다른 분류로 먼저 옮겨야 한다 (역할 분류 ROLE_CLASSIFICATION_IN_USE 선례)
    EVENT_CLASSIFICATION_IN_USE(
            HttpStatus.CONFLICT, "EVENT_CLASSIFICATION_IN_USE", "행사가 사용 중인 분류는 삭제할 수 없습니다."),

    /*
     * 409 — 이미 같은 코드의 분류가 있을 때. 선조회에 더해 동시 생성은 PK 충돌로만 드러나므로
     * 그 경로에서도 같은 코드로 옮긴다 (ROLE_CLASSIFICATION_CODE_DUPLICATED 선례).
     */
    EVENT_CLASSIFICATION_CODE_DUPLICATED(
            HttpStatus.CONFLICT, "EVENT_CLASSIFICATION_CODE_DUPLICATED", "이미 등록된 행사 분류 코드입니다."),

    /*
     * 409 — 폼이 연결되지 않은 행사의 신청 목록을 열려 할 때 (ssccops#146 · D11).
     *
     * 빈 목록을 돌려주지 않는 이유는 두 상태가 운영자에게 전혀 다른 일을 시키기 때문이다 —
     * 빈 배열은 "아직 아무도 신청하지 않았다"로 읽히지만 실제로는 신청을 받을 수단 자체가
     * 없는 상태이고, 해야 할 일은 기다리는 것이 아니라 폼을 연결하는 것이다.
     */
    EVENT_HAS_NO_FORM(HttpStatus.CONFLICT, "EVENT_HAS_NO_FORM", "폼이 연결되지 않은 행사입니다."),

    /*
     * 404 — 없는 참가자와 **다른 행사의 참가자 식별자**. 코드를 나누지 않는 것은 폼 응답의
     * 범위 검사(FORM_RESPONSE_NOT_FOUND)와 같은 판단이다 — 나누면 그 행사에 그 번호가 있는지가
     * 새어 나간다.
     */
    EVENT_PARTICIPANT_NOT_FOUND(
            HttpStatus.NOT_FOUND, "EVENT_PARTICIPANT_NOT_FOUND", "행사 참가자를 찾을 수 없습니다."),

    /*
     * 409 — 같은 회원을 같은 행사에 두 번 등록하려 할 때 (uk_event_ptcp_event_member).
     *
     * 선조회로 대부분 걸리지만 두 운영자가 같은 사람을 동시에 올리면 둘 다 통과하므로 UNIQUE
     * 위반도 같은 코드로 옮긴다 (#21 학번 중복 · FORM_ALREADY_LINKED와 같은 방식).
     */
    EVENT_PARTICIPANT_DUPLICATED(
            HttpStatus.CONFLICT, "EVENT_PARTICIPANT_DUPLICATED", "이미 이 행사에 등록된 회원입니다."),

    /*
     * 400 — 등록 요청의 근거가 둘 다 오거나 둘 다 없을 때 (formRspnsId · mbrId 상호 배타).
     *
     * 한쪽을 조용히 우선하지 않는 것은 그 선택이 form_rspns_id를 남길지 말지를 가르기 때문이다 —
     * 신청 근거는 나중에 "이 사람이 왜 명단에 있는가"를 답하는 유일한 값이라 추측으로 정할 수 없다.
     */
    INVALID_PARTICIPANT_SOURCE(
            HttpStatus.BAD_REQUEST,
            "INVALID_PARTICIPANT_SOURCE",
            "참가자 등록 근거는 응답 또는 회원 중 하나여야 합니다."),

    /*
     * 400 — 등록 상태로 CONFIRMED·WAITLISTED가 아닌 값이 왔을 때 (지금은 CANCELLED뿐이다).
     *
     * 취소는 등록의 결과가 아니라 확정된 참가자에게 일어나는 일이다 — 취소 상태로 시작하는
     * 행을 허용하면 "참가자였던 적이 없는 취소자"가 명단에 쌓인다.
     */
    INVALID_PARTICIPANT_REGISTRATION_STATUS(
            HttpStatus.BAD_REQUEST,
            "INVALID_PARTICIPANT_REGISTRATION_STATUS",
            "확정 또는 대기 상태로만 등록할 수 있습니다."),

    /*
     * 400 — 전이표(EventParticipantEntity.changeStatus)에 없는 참가 상태 전이 (D14).
     *
     * 허용하는 것은 WAITLISTED→CONFIRMED(승격)와 CONFIRMED→CANCELLED(취소) 둘뿐이다. 400인
     * 것은 폼·행사 상태 전이와 같은 판단이다 — 웹은 현재 상태를 이미 들고 있어 보낼 수 있는
     * 값이 정해진다.
     */
    INVALID_PARTICIPANT_STATUS_TRANSITION(
            HttpStatus.BAD_REQUEST,
            "INVALID_PARTICIPANT_STATUS_TRANSITION",
            "허용되지 않는 참가 상태 전이입니다."),

    /*
     * 409 — 아직 수락되지 않은 응답을 근거로 참가자를 등록하려 할 때 (D5).
     *
     * 심사와 등록은 나뉜 두 사건이며 순서가 있다 — 수락되지 않은 응답으로 명단에 올리면 폼
     * 응답의 심사 결과와 참가자 명단이 서로 다른 사실을 말하게 되고, 그 뒤에 반려가 나면
     * 명단에는 반려된 사람이 남는다. 400이 아니라 409인 것은 요청 형식이 아니라 대상의
     * 현재 상태가 문제라서다 (심사를 먼저 하면 같은 요청이 통과한다).
     */
    APPLICATION_NOT_ACCEPTED(
            HttpStatus.CONFLICT, "APPLICATION_NOT_ACCEPTED", "수락된 신청만 참가자로 등록할 수 있습니다."),

    /*
     * 413 — 본문(mtxt_cn)이 상한(10만 자)을 넘겼을 때.
     *
     * mtxt_cn은 TEXT라 DB가 제한하지 않는다. 상한이 없으면 붙여넣기 한 번으로 수 MB짜리 행이
     * 생기고 목록·상세 조회가 읽을 때마다 따라온다 (폼 RESPONSE_CONTENT_TOO_LARGE와 같은 판단).
     */
    EVENT_CONTENT_TOO_LARGE(
            HttpStatus.PAYLOAD_TOO_LARGE, "EVENT_CONTENT_TOO_LARGE", "행사 본문이 너무 큽니다."),

    /*
     * 400 — 요청한 확장자가 허용 목록에 없을 때 (#161 · D6).
     *
     * **뜻이 하나로 좁아졌다** (#210). 예전에는 "허용 목록 밖" 말고도 "요청의 contentType과
     * 확장자가 서로 어긋남"이 이 코드로 왔는데, 이제 요청이 신고하는 값이 확장자 하나뿐이라
     * 어긋날 짝이 없다. 운영자가 할 일은 그때나 지금이나 같다("허용되는 형식의 파일을 고르라").
     * 허용 목록은 EventImageType이 갖는다.
     */
    UNSUPPORTED_IMAGE_TYPE(HttpStatus.BAD_REQUEST, "UNSUPPORTED_IMAGE_TYPE", "지원하지 않는 이미지 형식입니다."),

    /*
     * 413 — 업로드하려는 이미지가 상한(10MB)을 넘겼을 때 (#161).
     *
     * 서버가 바이트를 보지 않으므로(D6) 이 판정의 근거는 **요청이 신고한 크기**다 — 실제 강제는
     * 버킷/도메인 정책의 몫이고, 여기서 끊는 것은 화면이 업로드를 시작하기 전에 안내하기
     * 위해서다. 본문 상한(EVENT_CONTENT_TOO_LARGE)과 같은 413이지만 대상이 다르다.
     */
    IMAGE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "IMAGE_TOO_LARGE", "이미지가 너무 큽니다."),

    /*
     * 404 — 행사 이미지 읽기 주소의 파일명이 우리가 발급한 형태가 아닐 때 (#208).
     *
     * 버킷은 비공개이고 읽기는 요청 시점에 서명하므로, 파일명은 곧 **무엇에 서명할지**를 정하는
     * 값이다. `../`나 경로 구분자가 낀 값이 키가 되면 같은 버킷의 학술 인증사진을 지목할 수
     * 있다(ssccops#156) — 그래서 형태가 어긋나면 서명을 만들기 전에 끊는다.
     *
     * 400이 아니라 404인 것은 이 경로의 응답을 하나로 묶기 위해서다. 미게시 행사·없는 행사가
     * 모두 404 EVENT_NOT_FOUND인 자리에서 형태만 상태 코드가 갈리면, 그 차이가 곧 "그 파일명은
     * 형태는 맞다"는 정보가 된다.
     */
    EVENT_IMAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "EVENT_IMAGE_NOT_FOUND", "행사 이미지를 찾을 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
