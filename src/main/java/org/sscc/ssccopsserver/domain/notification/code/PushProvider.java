package org.sscc.ssccopsserver.domain.notification.code;

/*
 * push_sbscrp.pvdr_cd — 구독을 받아 주는 푸시 제공자 (ssccops#446 · ADR-0045).
 *
 * 지금은 WEB_PUSH 하나다. 값이 하나뿐인데도 열을 둔 것은 ADR-0045 «뒤집는다면» — 네이티브
 * 앱을 내면 FCM/APNs 구독 행이 같은 표에 다른 제공자 코드로 들어오고 발송기가 하나 더 붙는다.
 * 그때 표·API·알림 행은 그대로이고 이 enum에 값 하나와 새 마이그레이션(CHECK 넓히기)이 는다.
 *
 * 값 목록과 V21의 CHECK 제약이 정본 한 쌍이다.
 */
public enum PushProvider {

    /** 표준 Web Push (RFC 8030 · 8291 · 8292) — 브라우저의 PushManager 구독 */
    WEB_PUSH
}
