package org.sscc.ssccopsserver.domain.notification.service;

/*
 * 발송 한 건의 결과 (ssccops#446). `WebPushSender`가 돌려주고 `PushDispatcher`가 읽는다.
 */
public enum WebPushOutcome {

    /** 푸시 서비스가 받았다(2xx). 브라우저까지 갔는지는 모른다 — 최선 노력 전송 */
    DELIVERED,

    /** 구독이 죽었다(404 · 410). 그 구독 행을 지운다 — 다시 보내도 같은 답이다 */
    GONE,

    /** 그 밖의 실패(네트워크 · 401/403 VAPID 거부 · 413 · 429 · 5xx). ERROR 로그로만 남긴다 */
    FAILED,

    /** 발송기가 꺼져 있다(NoopWebPushSender). 알림 행은 그대로 남고 푸시만 없다 */
    SKIPPED
}
