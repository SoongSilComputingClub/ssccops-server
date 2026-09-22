package org.sscc.ssccopsserver.domain.notification.dto;

import java.util.List;

import org.sscc.ssccopsserver.domain.notification.code.NotificationApp;

/*
 * `PUT /v1/notifications/types/{type}` 본문 (#535 · ADR-0047).
 *
 * **전체 교체다** — 체크박스 화면이 «지금 켜진 것 전부»를 보내고 서버가 그 유형의 행을 그대로
 * 맞춘다. 더하기/빼기 두 엔드포인트로 가르는 안은 기각: 화면이 체크박스라 «지금 상태»가 곧
 * 요청이고, 증분 API는 두 요청 사이에 남이 바꾼 값과 섞여 화면에 없던 조합을 만든다.
 *
 * **빈 배열·null은 400이다**(NotificationErrorCode.EMPTY_NOTIFICATION_ROUTE). 검사를 여기
 * `@NotEmpty`로 달지 않은 이유는 그 상수의 주석에 있다. «기준표에서 빼서 보낸 앱 규칙으로
 * 되돌리기»를 빈 배열로 표현하지 않는 것도 같은 판단이다 — 둘은 눈에 띄게 다른 조작이어야 한다.
 * **그 길은 `DELETE /v1/notifications/types/{type}`이다**(#537). DELETE가 생겼다고 여기의 400을
 * 풀지 않는다 — 되돌리기에 전용 조작이 있으므로 빈 배열은 여전히 «체크를 다 끄고 저장을 눌렀다»는
 * 실수이고, 그 실수를 받아 주면 알림이 조용히 꺼진다(ADR-0047 «최소 한 앱»).
 */
public record NotificationTypeRouteRequest(List<NotificationApp> apps) {}
