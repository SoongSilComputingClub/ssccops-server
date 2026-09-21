package org.sscc.ssccopsserver.domain.notification.code;

/*
 * push_sbscrp.app_cd · noti.app_cd — 세 앱 중 어느 것인가 (ssccops#446 · ADR-0045).
 *
 * 구독에서는 «어느 앱의 서비스워커가 등록했나»이고, 알림에서는 «링크 경로가 어느 앱의
 * 라우트인가»다. 서버는 앱의 origin을 모른다 — 절대 URL은 서비스워커가 자기 origin으로
 * 만들고(`ShareLinkResponse`가 URL을 조립하지 않는 것과 같은 이유), 서버가 아는 것은 이 코드와
 * 앱 안의 경로뿐이다.
 *
 * 값 목록과 V21의 CHECK 제약이 정본 한 쌍이다(두 표 모두).
 */
public enum NotificationApp {
    ADMIN,
    LMS,
    WWW
}
