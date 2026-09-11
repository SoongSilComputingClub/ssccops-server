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

    /*
     * 404 — 행사 자체를 찾을 수 없을 때. DRAFT·ARCHIVED도 관리 API에서는 존재하므로 여기 걸리지 않는다.
     *
     * **소프트 삭제된 행사도 여기에 걸린다** (#347 · ADR-0020). 없는 행사와 같은 코드로 묶는 것은
     * 공개 상세·공유 링크·이미지 리다이렉트가 링크만 가진 사람에게 답하는 자리이기 때문이다 —
     * 코드를 나누면 그 번호의 행사가 존재했다가 지워졌다는 사실이 새어 나가고, event_id는 연속된
     * 정수라 훑는 데 비용이 들지 않는다. 작성 중인 행사를 익명에게 없는 것으로 만드는 판단
     * (findByIdAndDeletedAtIsNullAndStatus)의 연장이다. 운영 경로(상세·수정·명단)도 같은 404다 —
     * 지워진 행사를 계속 고칠 수 있으면 "지웠다"의 뜻이 화면마다 달라진다.
     *
     * 반대로 삭제·복구 경로는 지워진 행사를 404가 아니라 409(EVENT_ALREADY_DELETED)로 끊는다 —
     * 그쪽은 휴지통을 이미 보고 있는 운영진이 부르는 자리라 숨길 것이 없다.
     */
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
     * 409 — 다른 행사에 이미 전속된 폼을 연결하려 할 때 (D11 · uk_event_form).
     *
     * 선조회로 대부분 걸리지만 두 행사가 같은 폼을 동시에 연결하면 둘 다 선조회를 통과하므로
     * UNIQUE 위반도 같은 코드로 옮긴다 (#21 학번 중복과 같은 방식).
     *
     * **폼 연결에 남은 409는 이것 하나다.** 신청이 발생한 뒤의 연결 변경을 막던
     * EVENT_FORM_IN_USE는 걷었다 (#336) — 왜 걷었는지는 EventServiceImpl.updateEvent의 연결
     * 변경 자리에 적혀 있다. 되살릴 코드를 여기 다시 만들기 전에 그 주석을 읽어라.
     *
     * **복구(POST /restore)도 이 코드다** (#347). 지운 행사는 폼을 붙잡지 않으므로 그 사이 다른
     * 행사가 같은 폼을 가져갈 수 있고, 그 뒤에 되살리면 폼 하나에 살아 있는 행사가 둘이 된다.
     * 복구 전용 코드를 따로 두지 않은 것은 사실이 같기 때문이다 — "그 폼은 이미 다른 행사의
     * 것이다". 선조회를 지나친 경합은 uk_event_form(부분 인덱스)이 잡아 같은 코드로 옮긴다.
     */
    FORM_ALREADY_LINKED(HttpStatus.CONFLICT, "FORM_ALREADY_LINKED", "이미 다른 행사에 연결된 폼입니다."),

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
     * 허용 목록은 ImageFileType이 갖는다.
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
    EVENT_IMAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "EVENT_IMAGE_NOT_FOUND", "행사 이미지를 찾을 수 없습니다."),

    /*
     * 502 — 행사 복제 중 본문 이미지를 R2에서 복사하지 못했을 때 (ssccops#198 · 결정 2).
     *
     * 복사는 트랜잭션 안에서 하므로 이 오류가 나면 행사 사본·폼 사본이 함께 롤백된다 — 이미지가
     * 없는 사본을 남기지 않는다. 원본 오브젝트가 **없는** 경우는 여기 걸리지 않는다(FileCopier 주석:
     * 원본에서 이미 깨진 참조가 복제를 막지 않는다). 502인 것은 우리 서버가 아니라 뒤의 저장소가
     * 답하지 않은 것이라서다.
     */
    EVENT_IMAGE_COPY_FAILED(
            HttpStatus.BAD_GATEWAY, "EVENT_IMAGE_COPY_FAILED", "행사 이미지를 복사하지 못했습니다."),

    /*
     * 409 — 학술 활동이 딸린 행사를 지우려 할 때 (#347 · ADR-0020).
     *
     * acdm_actv.event_id는 NOT NULL이라 행사가 사라지면 학술 프로그램이 고아가 된다. 소프트
     * 삭제는 행을 남기므로 FK 자체는 깨지지 않지만, 목록·상세에서 없는 행사가 된 것을 학술
     * 화면이 계속 가리키게 되어 "행사는 없는데 스터디는 있다"는 상태가 생긴다 — ADR-0014가
     * 하드 삭제에서 500으로 발견한 것을 이번에는 409로 끊는다. 학술 쪽에서 프로그램을 정리한
     * 뒤에야 지울 수 있다.
     *
     * 판정은 acdm_actv 행의 존재이지 행사의 분류나 제목이 아니다(AcademicEventLinkProvider) —
     * 분류로 유추하면 학술 활동도 일반 분류 "EVENT"를 쓰므로 아무것도 걸리지 않는다.
     *
     * 400이 아니라 409인 것은 요청 자체는 올바르고 행사의 현재 관계가 거절 이유이기 때문이다
     * (EVENT_CLASSIFICATION_IN_USE와 같은 판단).
     */
    EVENT_HAS_ACADEMIC_PROGRAM(
            HttpStatus.CONFLICT, "EVENT_HAS_ACADEMIC_PROGRAM", "학술 활동에 연결된 행사는 삭제할 수 없습니다."),

    /*
     * 409 — 이미 지워진 행사를 다시 지우려 할 때 (#347).
     *
     * 조회 계열이 지워진 행사를 없는 행사와 같은 404 EVENT_NOT_FOUND로 묶는 것과 **일부러
     * 갈린다.** 그쪽은 링크만 가진 사람에게 존재를 알려주지 않는 것이 목적이지만, 삭제·복구는
     * 휴지통을 이미 보고 있는 운영진이 부르는 경로라 "없는 행사"와 "이미 지운 행사"를 구별해
     * 줘야 다음에 할 일이 갈린다 — 앞은 목록을 새로고침할 일이고 뒤는 아무것도 할 일이 없다.
     * 그래서 삭제 경로의 조회는 del_dt 필터가 없는 조회를 쓴다.
     *
     * **코드 문자열이 열거형 이름과 다른 것은 의도다.** 폼(FORM_ALREADY_DELETED)·운영 도메인이
     * 이미 같은 상황에 "ALREADY_DELETED"를 쓰고 있어, 여기서 이름을 새로 지으면 화면이 "이미
     * 삭제됨"이라는 한 가지 사실에 도메인마다 다른 분기를 갖게 된다.
     */
    EVENT_ALREADY_DELETED(HttpStatus.CONFLICT, "ALREADY_DELETED", "이미 삭제된 행사입니다."),

    /*
     * 409 — 지워지지 않은 행사를 되살리려 할 때 (#347).
     *
     * EVENT_ALREADY_DELETED의 대칭이다. 둘 다 조용히 통과시키면(멱등) 두 운영진이 같은 휴지통을
     * 열고 있을 때 뒤에 누른 쪽이 자기가 되살렸다고 믿는데 실제로는 아무 일도 하지 않은 상태가
     * 되고, 그 차이는 화면에 드러나지 않는다. 코드 문자열은 폼(FORM_NOT_DELETED)과 같다.
     */
    EVENT_NOT_DELETED(HttpStatus.CONFLICT, "NOT_DELETED", "삭제되지 않은 행사입니다."),

    /*
     * 409 — 게시 전(DRAFT)이 아닌 행사에 공유 링크를 발급하려 할 때 (ssccops#312 · ADR-0016).
     *
     * **게시된 행사에는 발급하지 않는다.** 이미 익명이 여는 주소가 있어(`/events/{eventId}`)
     * 토큰이 더하는 것은 폐기 기능뿐인데, 그 폐기가 원본 공개 URL을 막지 못한다 — "공유를
     * 중지했다"는 화면의 표시가 사실이 아니게 되므로 지키지 못하는 것을 지킨다고 말하는 버튼이
     * 된다. 거절이 아무 수단도 빼앗지 않는다는 것이 근거의 나머지 절반이다: 게시된 행사를
     * 공유할 길은 이미 있고 그쪽이 더 낫다.
     *
     * **보관된(ARCHIVED) 행사에도 발급하지 않는다.** 보관은 끝난 행사를 공개에서 내리는 자리라
     * (ADR-0020 — 잘못 만든 것을 치우는 자리는 삭제로 다시 갈라졌다), 내린 것을 익명에게 다시
     * 여는 것은 새로 만드는 노출이다. 지워진 행사는 발급 전 조회에서 이미 404다.
     *
     * 400이 아니라 409인 것은 요청 형식이 아니라 대상의 현재 상태가 문제라서다 — 게시를
     * 철회하면(RETRACT) 같은 요청이 통과한다 (APPLICATION_NOT_ACCEPTED와 같은 판단).
     */
    EVENT_SHARE_NOT_DRAFT(
            HttpStatus.CONFLICT, "EVENT_SHARE_NOT_DRAFT", "게시 전 행사만 공유 링크를 발급할 수 있습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
