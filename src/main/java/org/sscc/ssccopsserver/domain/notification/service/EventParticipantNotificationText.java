package org.sscc.ssccopsserver.domain.notification.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.sscc.ssccopsserver.domain.event.entity.EventParticipantEntity;
import org.sscc.ssccopsserver.domain.notification.code.NotificationType;

/*
 * 행사 참가 상태 알림의 문구와 링크 (#528 · ssccops#453).
 *
 * 제목은 «[참가 확정] {행사명}» 꼴, 내용은 «{행사명} · 변경 {일시}» 한 줄이다. 일시는 명단 행의
 * mdfcn_dt — 전이가 그 값을 갱신하고 이 알림은 그 직후에 만들어진다. 정원·대기 순번은 싣지
 * 않는다(순번은 신청자에게 비공개다 — EventParticipantStatus D5).
 *
 * 링크는 www의 «내 신청» `/me/applications` 하나다 — 행사별 상세가 아니라 목록인 것은 그 화면이
 * 참가 상태를 한눈에 보이는 자리이기 때문이다. 서버가 origin을 모르므로 경로만 싣는다.
 */
final class EventParticipantNotificationText {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter CHANGED_AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(SERVICE_ZONE);

    static final String APPLICATIONS_PATH = "/me/applications";

    private EventParticipantNotificationText() {}

    static String title(NotificationType type, EventParticipantEntity participant) {
        return prefix(type) + " " + participant.getEvent().getTitle();
    }

    static String body(EventParticipantEntity participant) {
        return participant.getEvent().getTitle()
                + " · 변경 "
                + changedAtText(participant.getUpdatedAt());
    }

    static String linkPath() {
        return APPLICATIONS_PATH;
    }

    private static String prefix(NotificationType type) {
        return switch (type) {
            case APPLICATION_CONFIRMED -> "[참가 확정]";
            case APPLICATION_WAITLISTED -> "[대기]";
            case APPLICATION_CANCELLED -> "[취소]";
            default -> throw new IllegalArgumentException("not a participant type: " + type);
        };
    }

    private static String changedAtText(Instant changedAt) {
        return changedAt == null ? "-" : CHANGED_AT.format(changedAt);
    }
}
