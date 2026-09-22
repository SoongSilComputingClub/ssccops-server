package org.sscc.ssccopsserver.domain.notification.dto;

import java.util.List;

import org.sscc.ssccopsserver.domain.notification.code.NotificationApp;
import org.sscc.ssccopsserver.domain.notification.code.NotificationType;

/*
 * 어드민 «설정 › 알림 유형»의 한 줄 (#535 · ADR-0047).
 *
 * **유형 목록은 enum에서, 앱은 기준표에서 온다.** 유형은 코드가 아는 닫힌 집합이라 표에 없는
 * 유형도 줄로 나와야 하고(그러지 않으면 아직 정하지 않은 유형을 화면에서 정할 수 없다), 앱은
 * 표가 아는 값이다.
 *
 * `followsSendingApp`이 true면 기준표에 행이 **하나도 없는** 유형이고 그때 `apps`는 빈 배열이다 —
 * 화면은 그 줄을 «보낸 앱을 따릅니다»로 그리고 체크박스는 전부 꺼진 상태로 둔다. 필드 이름을
 * `seeded`로 두는 안은 기각했다: 답해야 하는 것은 «시드로 들어왔는가»(운영진에게 뜻이 없는 사실)가
 * 아니라 «지금 이 유형이 어느 규칙으로 도는가»이고, 운영진이 손으로 넣은 행도 시드와 똑같이
 * 기준표의 행이다.
 */
public record NotificationTypeRouteResponse(
        NotificationType type,
        String label,
        List<NotificationApp> apps,
        boolean followsSendingApp) {

    /** 기준표가 정한 유형. apps는 화면 순서를 고정하려고 enum 선언 순으로 정렬해 넘긴다 */
    public static NotificationTypeRouteResponse routed(
            NotificationType type, List<NotificationApp> apps) {
        return new NotificationTypeRouteResponse(type, type.label(), apps, false);
    }

    /** 기준표에 행이 없는 유형 — 그 알림 행의 app을 따른다 */
    public static NotificationTypeRouteResponse followingSendingApp(NotificationType type) {
        return new NotificationTypeRouteResponse(type, type.label(), List.of(), true);
    }
}
