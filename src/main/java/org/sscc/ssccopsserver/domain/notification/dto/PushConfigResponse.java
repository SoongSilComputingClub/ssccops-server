package org.sscc.ssccopsserver.domain.notification.dto;

/*
 * GET /v1/push/config — 브라우저가 PushManager.subscribe에 넣을 VAPID 공개키 (ssccops#446).
 *
 * `publicKey`가 null이면 이 서버는 푸시를 보내지 않는다(키가 없거나 `ssccops.push.enabled=false`).
 * 웹은 그때 구독 토글을 그리지 않는다 — 구독을 받아 두어도 보낼 수 없는 구독이라 뜻이 없다.
 */
public record PushConfigResponse(String publicKey) {}
