package org.sscc.ssccopsserver.domain.notification.push;

import org.sscc.ssccopsserver.domain.notification.entity.PushSubscriptionEntity;
import org.sscc.ssccopsserver.domain.notification.service.WebPushOutcome;
import org.sscc.ssccopsserver.domain.notification.service.WebPushSender;

/*
 * 보내지 않는 발송기 (ssccops#446). `ssccops.push.enabled=false`(test 프로필)이거나 VAPID 키가
 * 비어 있을 때 선다 — 알림 행은 그대로 만들어지고 푸시만 없다.
 *
 * 컨텍스트가 뜨려면 어느 쪽이든 `WebPushSender` 빈이 있어야 하고, 키 없는 환경(기여자 로컬 · 아직
 * 키를 넣지 않은 배포 · CI)이 «알림은 있는데 푸시는 없는 서버»로 그대로 돌아가야 한다.
 */
public class NoopWebPushSender implements WebPushSender {

    @Override
    public WebPushOutcome send(PushSubscriptionEntity subscription, String payloadJson) {
        return WebPushOutcome.SKIPPED;
    }
}
