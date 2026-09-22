package org.sscc.ssccopsserver.domain.notification.code.error;

import org.springframework.http.HttpStatus;
import org.sscc.ssccopsserver.global.apipayload.code.error.ErrorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

/*
 * 알림 도메인 전용 에러 코드 (ssccops#446).
 *
 * 코드 문자열은 운영 도메인과 같은 영문 UPPER_SNAKE_CASE이며 정의서 03_오류_코드의 어휘를 쓴다 —
 * 프론트가 코드 문자열로 분기하므로 새 문자열을 만들지 않는다. 예외는 RATE_LIMITED 하나이며
 * 이유는 그 상수의 주석에 있다(#528).
 */
@Getter
@AllArgsConstructor
public enum NotificationErrorCode implements ErrorCode {

    /*
     * 404 — 알림이 없다.
     *
     * **남의 알림도 이 하나다.** 알림은 자기 것만 다루는 자원이라 «있는데 네 것이 아니다»(403)를
     * 나누면 식별자가 연속 정수인 표에서 어느 번호가 존재하는지가 드러난다. `@RequireAuthority`가
     * 403으로 답하는 것(AGENTS.md «404로 감추지 않는다»)은 **권한** 판정이고, 이쪽은 소유 판정이다 —
     * 회원 본인 자원(공유 토큰·응답)이 같은 태도를 취한다.
     */
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "NOT_FOUND", "알림을 찾을 수 없습니다."),

    /*
     * 400 — 목록 커서를 해독할 수 없다. 형식이 깨진 커서다(OperationErrorCode.INVALID_CURSOR와
     * 같은 판단 — 첫 페이지로 조용히 되돌리면 클라이언트가 무한히 처음부터 다시 받는다).
     */
    INVALID_CURSOR(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "잘못된 커서입니다."),

    /*
     * 429 — 테스트 알림을 1분에 세 번 넘게 보냈다 (#528 · ssccops#454).
     *
     * 버튼 연타가 곧 푸시 서비스 호출 연타라 회원당 분 한도를 둔다(TestNotificationRateLimiter).
     * 코드 문자열 RATE_LIMITED는 이 자리에서 새로 만든 것이다 — 규정 도우미의
     * ASSISTANT_RATE_LIMITED는 그 도메인의 접두어가 붙은 값이라 그대로 빌리면 화면이 규정 도우미
     * 문구를 고르고, 회원 계정 연결의 TOO_MANY_LINK_ATTEMPTS도 뜻이 다르다. 웹은 이 문자열로
     * «잠시 뒤 다시»를 고른다.
     */
    /*
     * 404 — 그런 알림 유형이 없다 (#535 · ADR-0047).
     *
     * `PUT`·`DELETE /v1/notifications/types/{type}`의 경로 값이 NotificationType에 없을 때다. 경로를
     * enum으로 받지 않고 문자열로 받아 여기서 판정하는 것은, enum 변환 실패가 스프링 기본
     * ProblemDetail(봉투 없는 본문)로 나가기 때문이다 — 유형 목록은 닫힌 집합이라 «없는 유형»은
     * 형식 오류가 아니라 없는 자원이다.
     *
     * **«기준표에 행이 없다»는 여기 오지 않는다**(#537). 그것은 정상 상태(= 보낸 앱을 따른다)라
     * DELETE가 200으로 답한다 — 둘을 같은 404로 묶으면 화면이 «유형 코드가 틀렸다»와 «이미
     * 되돌아가 있다»를 가르지 못한다.
     */
    NOTIFICATION_TYPE_NOT_FOUND(HttpStatus.NOT_FOUND, "NOT_FOUND", "알림 유형을 찾을 수 없습니다."),

    /*
     * 400 — 수신 앱을 하나도 주지 않았다 (#535 · ADR-0047 «최소 한 앱»).
     *
     * 빈 배열은 «이 유형의 알림을 아무 앱에도 보이지 않게 한다»인데, 기준표에서 그 상태는
     * «행이 없음»(= 보낸 앱을 따른다)과 구별되지 않으면서 알림을 조용히 끈다. 화면도 같은 것을
     * 막지만(ADR-0047) 서버가 마지막 자리다.
     *
     * `@NotEmpty`를 DTO에 달지 않은 것은 판정을 두 벌로 두지 않기 위해서다 — 달면 인가 애스펙트가
     * 돌기 전에 400이 나가 «권한 없는 사람이 빈 배열을 보내면 403이 아니라 400»이 되고, 무엇보다
     * 이 규칙이 ADR의 결정이라 서비스 계약에 있어야 한다.
     *
     * **DELETE가 생긴 뒤에도 이 400은 그대로다**(#537). 되돌리기에 전용 조작이 있으므로 빈 배열은
     * «되돌리고 싶다»가 아니라 «체크를 다 끄고 저장을 눌렀다»는 실수로 읽는 것이 맞다.
     */
    EMPTY_NOTIFICATION_ROUTE(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "수신 앱을 최소 한 개 지정해야 합니다."),

    TEST_NOTIFICATION_RATE_LIMITED(
            HttpStatus.TOO_MANY_REQUESTS,
            "RATE_LIMITED",
            "테스트 알림은 1분에 3번까지 보낼 수 있습니다. 잠시 뒤 다시 시도해 주세요.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
