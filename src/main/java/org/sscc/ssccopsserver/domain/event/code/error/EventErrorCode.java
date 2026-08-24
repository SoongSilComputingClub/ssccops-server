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
     * 413 — 본문(mtxt_cn)이 상한(10만 자)을 넘겼을 때.
     *
     * mtxt_cn은 TEXT라 DB가 제한하지 않는다. 상한이 없으면 붙여넣기 한 번으로 수 MB짜리 행이
     * 생기고 목록·상세 조회가 읽을 때마다 따라온다 (폼 RESPONSE_CONTENT_TOO_LARGE와 같은 판단).
     */
    EVENT_CONTENT_TOO_LARGE(
            HttpStatus.PAYLOAD_TOO_LARGE, "EVENT_CONTENT_TOO_LARGE", "행사 본문이 너무 큽니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
