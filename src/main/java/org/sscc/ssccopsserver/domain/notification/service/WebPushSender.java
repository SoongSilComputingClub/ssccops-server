package org.sscc.ssccopsserver.domain.notification.service;

import java.util.Optional;

import org.sscc.ssccopsserver.domain.notification.entity.PushSubscriptionEntity;

/*
 * Web Push 발송기 — 알림 도메인이 선언하는 포트 (ssccops#446 · ADR-0045).
 *
 * 구현은 둘이다. `push/VapidWebPushSender`(RFC 8291 암호화 + RFC 8292 VAPID 서명 + HTTP POST)와
 * `push/NoopWebPushSender`(키가 없거나 `ssccops.push.enabled=false` — test 프로필). 어느 쪽이
 * 서는지는 `push/WebPushSenderConfig`가 부팅 때 정하고 로그 한 줄로 알린다.
 *
 * **결과는 셋으로 접는다**(`WebPushOutcome`). 호출부(`PushDispatcher`)가 아는 것은 «갔다 ·
 * 구독이 죽었다(지워라) · 실패했다(로그)» 뿐이며 HTTP 상태 코드는 여기서 끝난다 — 발송 이력 표를
 * 두지 않기로 했으므로(ADR-0045) 결과를 더 잘게 나눠도 담을 자리가 없다.
 *
 * 인터페이스로 둔 것은 네이티브 앱(FCM/APNs)이 붙을 때 `PushProvider`별 구현이 늘어나는 자리를
 * 미리 만든 것이 아니라, **테스트에서 발송기를 빼기 위해서**다 — 실제 푸시 서비스에 붙는
 * 테스트는 없다(브라우저가 있어야 구독이 생긴다).
 */
public interface WebPushSender {

    /**
     * 구독 하나에 페이로드(JSON · 4KB 이하)를 보낸다. 던지지 않는다 — 실패는 결과값이다.
     *
     * @param subscription endpoint·키를 든 구독
     * @param payloadJson 서비스워커가 받을 JSON 문자열
     */
    WebPushOutcome send(PushSubscriptionEntity subscription, String payloadJson);

    /**
     * 브라우저가 구독할 때 넣을 VAPID 공개키. 보내지 않는 발송기는 비어 있다 — 그때 웹은 구독 토글을 그리지 않는다(`GET /v1/push/config`).
     */
    default Optional<String> publicKey() {
        return Optional.empty();
    }
}
